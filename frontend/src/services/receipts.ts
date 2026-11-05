import { api, apiBlob, apiObjectUrl, ApiError } from '@/api/client'
import type { Id, ReceiptScan, Transaction } from '@/api/types'
import type { TransactionRec } from '@/data/schema'
import { today, type IsoDate } from '@/domain/dates'
import { CategorySuggester } from '@/domain/categorySuggester'
import { toCents, fromCents } from '@/domain/money'
import { truncate } from '@/domain/text'
import { db } from './context'
import { transactionDto } from './mappers'
import { matchPending } from './recurring'
import { clearReceiptScan, requireTransaction } from './transactions'

/**
 * Comprovantes. A foto vai para o servidor, que guarda, lê o QR code e roda o modelo de visão (ou põe na fila do
 * worker); de volta vem só o que foi lido. Tudo o que depende dos dados do usuário é calculado aqui, como antes era
 * no servidor: data e total do lançamento, categoria pelas regras, duplicata e vencimento recorrente que o cupom
 * parece pagar. A leitura adiada (worker) é aplicada no lançamento salvo por {@link processPendingReceipts}.
 */

/** O que o modelo leu (ReceiptScan do servidor; valores em reais). */
interface RawScan {
  merchant: string
  issueDate: IsoDate | null
  dueDate: IsoDate | null
  paid: boolean
  total: number | null
  paymentMethod: string
  accessKey: string | null
  items: { description: string; quantity: number | null; unitPrice: number | null; total: number }[]
}

interface ReceiptRead {
  receiptFile: string
  jobId: number | null
  status: 'DONE' | 'PENDING' | 'FAILED'
  workerOnline: boolean
  error: string | null
  qrAccessKey: string | null
  scan: RawScan | null
}

interface JobResult {
  jobId: number
  transactionId: Id
  receiptFile: string
  status: 'DONE' | 'PENDING' | 'FAILED'
  error: string | null
  qrAccessKey: string | null
  scan: RawScan | null
}

// ---------- Chave de acesso (porte de AccessKey) ----------

export function isValidAccessKey(key: string | null | undefined): key is string {
  if (!key || !/^\d{44}$/.test(key)) return false
  let sum = 0
  let weight = 2
  for (let i = 42; i >= 0; i--) {
    sum += Number(key[i]) * weight
    weight = weight === 9 ? 2 : weight + 1
  }
  let dv = 11 - (sum % 11)
  if (dv >= 10) dv = 0
  return Number(key[43]) === dv
}

/** O modelo às vezes erra o ano; quando a chave confirma o mês lido, vale o ano da chave. */
export function fixYear(date: IsoDate | null, key: string | null): IsoDate | null {
  if (!date || !isValidAccessKey(key)) return date
  const year = 2000 + Number(key.slice(2, 4))
  const month = Number(key.slice(4, 6))
  if (month === Number(date.slice(5, 7)) && year !== Number(date.slice(0, 4))) {
    return `${String(year).padStart(4, '0')}${date.slice(4)}`
  }
  return date
}

// ---------- Interpretação da leitura ----------

const itemsTotalCents = (scan: RawScan) => scan.items.reduce((s, i) => s + toCents(i.total), 0)

/** Total do cupom; sem ele, a soma dos itens. */
function totalCents(scan: RawScan): number | null {
  if (scan.total != null && scan.total > 0) return toCents(scan.total)
  const items = itemsTotalCents(scan)
  return items > 0 ? items : null
}

/** Data do lançamento: vencimento (fatura); senão emissão/compra, com o ano corrigido pela chave. */
function readDate(scan: RawScan, key: string | null): IsoDate | null {
  return scan.dueDate ?? fixYear(scan.issueDate, key)
}

/** Categoria pelas regras do usuário: primeiro pelo estabelecimento, depois pelos itens. */
export function suggestCategory(scan: Pick<RawScan, 'merchant' | 'items'>) {
  const s = db()
  const suggester = new CategorySuggester(s.list('category'), s.list('categoryRule'))
  let category = suggester.suggest(scan.merchant, 'EXPENSE', null)
  if (!category && scan.items.length) {
    category = suggester.suggest(scan.items.slice(0, 10).map((i) => i.description).join(' '), 'EXPENSE', null)
  }
  return category
}

const sameMerchant = (t: TransactionRec, merchant: string) => {
  if (!merchant?.trim()) return false
  const a = merchant.trim().toLowerCase()
  return t.merchant?.trim().toLowerCase() === a || t.description?.trim().toLowerCase() === a
}

const sameItems = (t: TransactionRec, scan: RawScan) => {
  if (!scan.items.length || t.items.length !== scan.items.length) return false
  const a = t.items.map((i) => i.total).sort((x, y) => x - y)
  const b = scan.items.map((i) => toCents(i.total)).sort((x, y) => x - y)
  return a.every((v, i) => v === b[i])
}

/** Monta a resposta do formulário (como o `complete` do antigo ReceiptService). */
function toScanDto(read: ReceiptRead, ignoreTransactionId: Id | null): ReceiptScan {
  const empty: ReceiptScan = {
    receiptFile: read.receiptFile, merchant: '', date: null, paid: true, total: null, itemsTotal: 0, paymentMethod: '',
    accessKey: null, items: [], suggestedCategoryId: null, suggestedCategoryName: null, duplicateOfTransactionId: null,
    duplicateOfDescription: null, duplicateOfDate: null, recurringMatchTransactionId: null, recurringMatchDescription: null,
    recurringMatchDate: null, recurringMatchAmount: null, recurringMatchCategoryId: null, recurringMatchAccountId: null,
    jobId: read.jobId, status: read.status, workerOnline: read.workerOnline, error: read.error,
  }
  const scan = read.scan
  if (read.status !== 'DONE' || !scan) return empty

  const s = db()
  const accessKey = read.qrAccessKey ?? scan.accessKey
  const date = readDate(scan, accessKey) ?? today()
  const total = totalCents(scan)
  const category = suggestCategory(scan)
  const txs = s.list('transaction')

  let duplicate: TransactionRec | null = accessKey ? txs.find((t) => t.receiptAccessKey === accessKey) ?? null : null
  if (!duplicate && total != null) {
    // Sem chave legível, mesma data e mesmo valor já lançados valem como provável duplicata; entre vários, prefere o
    // do mesmo estabelecimento (e os mesmos itens).
    const similarity = (t: TransactionRec) => (sameMerchant(t, scan.merchant) ? 2 : 0) + (sameItems(t, scan) ? 1 : 0)
    const same = txs.filter((t) => t.type === 'EXPENSE' && t.dueDate === date && t.amount === total && t.id !== ignoreTransactionId)
    same.sort((a, b) => similarity(b) - similarity(a) || (a.createdAt < b.createdAt ? -1 : 1))
    duplicate = same[0] ?? null
  }
  if (duplicate && duplicate.id === ignoreTransactionId) duplicate = null

  // Sem duplicata e lançando algo novo: o comprovante pode pagar um vencimento recorrente pendente.
  let occurrence: TransactionRec | null = null
  if (!duplicate && ignoreTransactionId == null && total != null) {
    const text = `${scan.merchant} ${scan.items.slice(0, 10).map((i) => i.description).join(' ')}`
    const cand = { description: text, amount: total, type: 'EXPENSE' as const, date }
    occurrence = matchPending([cand], (c) => c, date, date).get(cand)?.occurrence ?? null
  }

  return {
    ...empty,
    merchant: scan.merchant,
    date,
    paid: scan.paid,
    total: fromCents(total),
    itemsTotal: fromCents(itemsTotalCents(scan)),
    paymentMethod: scan.paymentMethod,
    accessKey,
    items: scan.items,
    suggestedCategoryId: category?.id ?? null,
    suggestedCategoryName: category?.name ?? null,
    duplicateOfTransactionId: duplicate?.id ?? null,
    duplicateOfDescription: duplicate?.description ?? null,
    duplicateOfDate: duplicate?.dueDate ?? null,
    recurringMatchTransactionId: occurrence?.id ?? null,
    recurringMatchDescription: occurrence?.description ?? null,
    recurringMatchDate: occurrence?.dueDate ?? null,
    recurringMatchAmount: fromCents(occurrence?.amount ?? null),
    recurringMatchCategoryId: occurrence?.categoryId ?? null,
    recurringMatchAccountId: occurrence?.accountId ?? null,
  }
}

/**
 * Leva o que o modelo leu para um lançamento já salvo, sem sobrescrever o que o usuário preencheu. Total ou data
 * diferentes do lançado viram aviso (DIVERGENT) para o usuário decidir. Porte de ReceiptJobService.applyTo.
 */
export function applyScan(t: TransactionRec, scan: RawScan, qrAccessKey: string | null): TransactionRec {
  const out = structuredClone(t)
  const key = qrAccessKey ?? scan.accessKey
  if (!out.items.length && scan.items.length) {
    out.items = scan.items.map((i) => ({
      description: truncate(i.description, 140),
      quantity: i.quantity,
      unitPrice: i.unitPrice == null ? null : toCents(i.unitPrice),
      total: toCents(i.total),
    }))
  }
  if (!out.merchant?.trim() && scan.merchant?.trim()) out.merchant = truncate(scan.merchant, 120)
  if (!out.receiptAccessKey && key) out.receiptAccessKey = key
  if (!out.categoryId && out.type === 'EXPENSE') out.categoryId = suggestCategory(scan)?.id ?? null

  const total = totalCents(scan)
  const date = readDate(scan, key)
  const totalDiffers = total != null && total !== out.amount
  const dateDiffers = date != null && date !== out.dueDate
  clearReceiptScan(out)
  if (totalDiffers || dateDiffers) {
    out.receiptScanStatus = 'DIVERGENT'
    out.receiptScanTotal = totalDiffers ? total : null
    out.receiptScanDate = dateDiffers ? date : null
  }
  return out
}

// ---------- Chamadas ao servidor ----------

export async function scanReceipt(file: File, ignoreTransactionId: Id | null): Promise<ReceiptScan> {
  const form = new FormData()
  form.append('file', file)
  const read = await api<ReceiptRead>('/api/receipts/scan', { method: 'POST', body: form })
  return toScanDto(read, ignoreTransactionId)
}

export async function receiptJob(jobId: number, ignoreTransactionId: Id | null): Promise<ReceiptScan> {
  return toScanDto(await api<ReceiptRead>(`/api/receipts/jobs/${jobId}`), ignoreTransactionId)
}

/** Aplica no lançamento o estado da leitura ligada à foto dele. */
function applyResult(t: TransactionRec, res: JobResult | null) {
  if (!res) {
    // Nenhuma leitura para a foto (provedor direto, ou foto sem leitura): nada a esperar.
    if (t.receiptScanStatus === 'PENDING') db().put('transaction', { ...t, receiptScanStatus: null })
    return
  }
  if (res.status === 'PENDING') {
    if (t.receiptScanStatus !== 'PENDING') db().put('transaction', { ...t, receiptScanStatus: 'PENDING', receiptScanTotal: null, receiptScanDate: null })
  } else if (res.status === 'FAILED') {
    if (t.receiptScanStatus !== 'FAILED') db().put('transaction', { ...t, receiptScanStatus: 'FAILED', receiptScanTotal: null, receiptScanDate: null })
  } else if (res.scan) {
    db().put('transaction', applyScan(t, res.scan, res.qrAccessKey))
  } else if (t.receiptScanStatus === 'PENDING') {
    // Pronta e já vista pelo formulário quando o lançamento foi salvo.
    db().put('transaction', { ...t, receiptScanStatus: null })
  }
}

async function attach(t: TransactionRec): Promise<JobResult | null> {
  return (await api<JobResult | undefined>('/api/receipts/attach', {
    method: 'POST', body: { receiptFile: t.receiptFile, transactionId: t.id },
  })) ?? null
}

/**
 * O lançamento acabou de ser salvo com uma foto nova: liga a leitura dela a ele. Sem rede, fica marcado como
 * pendente e é ligado na próxima sincronização ({@link processPendingReceipts}).
 */
export async function attachReceipt(transactionId: Id) {
  const t = db().get('transaction', transactionId)
  if (!t?.receiptFile) return
  try {
    applyResult(t, await attach(t))
  } catch (e) {
    if (e instanceof ApiError && e.status === 0) {
      db().put('transaction', { ...t, receiptScanStatus: 'PENDING', receiptScanTotal: null, receiptScanDate: null })
    } else {
      throw e
    }
  }
}

/** Depois de cada sincronização: confere as leituras pendentes (de qualquer aparelho) e aplica as que terminaram. */
export async function processPendingReceipts() {
  const pending = db().list('transaction').filter((t) => t.receiptScanStatus === 'PENDING' && t.receiptFile)
  for (const t of pending) {
    try {
      const res = await attach(t)
      const current = db().get('transaction', t.id)
      if (current) applyResult(current, res)
    } catch {
      return
    }
  }
  if (pending.length) await db().flush()
}

/** "Ler de novo": a foto do lançamento volta para a fila do worker. */
export async function retryReceipt(transactionId: Id): Promise<Transaction> {
  const t = requireTransaction(transactionId)
  if (!t.receiptFile) throw new ApiError(422, 'Este lançamento não tem comprovante para ler')
  await api<JobResult>('/api/receipts/retry', { method: 'POST', body: { receiptFile: t.receiptFile, transactionId: t.id } })
  const next: TransactionRec = { ...t, receiptScanStatus: 'PENDING', receiptScanTotal: null, receiptScanDate: null }
  db().put('transaction', next)
  return transactionDto(next, { withInvoice: true })
}

export function receiptImageUrl(file: string) {
  return apiObjectUrl(`/api/receipts/${encodeURIComponent(file)}`)
}

export async function downloadReceipt(file: string): Promise<Uint8Array | null> {
  try {
    return new Uint8Array(await (await apiBlob(`/api/receipts/${encodeURIComponent(file)}`, 'o comprovante')).arrayBuffer())
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null
    throw e
  }
}

export async function uploadReceipt(bytes: Uint8Array, originalName: string): Promise<string> {
  const form = new FormData()
  form.append('file', new Blob([bytes as BlobPart], { type: 'image/jpeg' }), originalName)
  return (await api<{ receiptFile: string }>('/api/receipts', { method: 'POST', body: form })).receiptFile
}
