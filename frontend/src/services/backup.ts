import { strFromU8, strToU8, unzipSync, zipSync, type Zippable } from 'fflate'
import type { BackupRestored } from '@/api/types'
import type {
  AccountRec, AccountType, CategoryRec, CategoryRuleRec, Frequency, Id, ItemRec, RecurringBillRec, TransactionRec,
  TransactionType, TransferRec,
} from '@/data/schema'
import { nowIso } from '@/domain/dates'
import { newId, occurrenceId } from '@/domain/ids'
import { fromCents, toCents, type Cents } from '@/domain/money'
import { normalize, truncate } from '@/domain/text'
import { BusinessError, db } from './context'
import { emptyTransaction } from './transactions'
import { invalidateEnsured } from './recurring'

/**
 * Exportação e importação dos dados da conta (porte de BackupService), agora no aparelho: contas, categorias,
 * regras, recorrentes e lançamentos (com itens, fotos e transferências). Não é um dump: o arquivo não carrega ids,
 * as referências são por nome (conta, categoria "Pai › Filha") e por posição dentro do próprio arquivo (par de uma
 * transferência, regra recorrente). Valores em reais no JSON, como o servidor exportava.
 *
 * A importação SOMA ao que já existe: contas e categorias são casadas pelo nome e criadas se faltarem; lançamento
 * igual a um já existente (mesma conta, data, tipo, valor e descrição, ou mesmo identificador de extrato, ou a
 * mesma ocorrência de recorrente) é pulado. Importar o mesmo arquivo duas vezes não duplica nada.
 *
 * As fotos dos comprovantes ficam no servidor: quem chama injeta o download (exportação) e o upload (importação).
 */

export const FORMAT = 'contaslt-data'
export const VERSION = 1
const JSON_ENTRY = 'contaslt.json'
const RECEIPTS_DIR = 'receipts/'
const SEP = ' › '

export interface ExportIo {
  /** Bytes da foto guardada no servidor, ou null se ela sumiu. */
  downloadReceipt(file: string): Promise<Uint8Array | null>
}

export interface ImportIo {
  /** Sobe a foto e devolve o nome com que o servidor a guardou. */
  uploadReceipt(bytes: Uint8Array, originalName: string): Promise<string>
}

// ---------- Formato do arquivo ----------

type Json = Record<string, unknown>

interface FileAccount {
  name: string
  type: AccountType
  initialBalance: number
  color: string
  archived: boolean
  closingDay: number | null
  dueDay: number | null
}

interface FileCategory {
  name: string
  type: TransactionType
  color: string
  icon: string
  archived: boolean
  parent: string | null
}

interface FileBill {
  description: string
  amount: number
  type: TransactionType
  frequency: Frequency
  startDate: string
  endDate: string | null
  occurrences: number | null
  notes: string | null
  active: boolean
  startNumber: number
  matchStrictness: number
  account: string | null
  category: string | null
}

interface FileTransaction {
  description: string
  amount: number
  type: TransactionType
  date: string
  paid: boolean
  account: string
  category: string | null
  notes: string | null
  externalId: string | null
  merchant: string | null
  receipt: string | null
  receiptAccessKey: string | null
  recurringBill?: number
  installmentNumber?: number | null
  scheduledDate?: string | null
  cardAccount?: string | null
  invoiceMonth?: string | null
  transferTo?: number
  items: { description: string; quantity: number | null; unitPrice: number | null; total: number }[]
}

// ---------- Exportação ----------

const byCreation = (a: { createdAt: string; id: Id }, b: { createdAt: string; id: Id }) =>
  a.createdAt < b.createdAt ? -1 : a.createdAt > b.createdAt ? 1 : a.id < b.id ? -1 : a.id > b.id ? 1 : 0

/** Zip com o JSON primeiro (o servidor antigo lia nessa ordem) e as fotos dos comprovantes depois. */
export async function exportBackup(io: ExportIo): Promise<Uint8Array> {
  const root = snapshot()
  const entries: Zippable = { [JSON_ENTRY]: strToU8(JSON.stringify(root, null, 2)) }
  const done = new Set<string>()
  for (const t of root.transactions) {
    const file = t.receipt
    if (!file || done.has(file)) continue
    done.add(file)
    const bytes = await io.downloadReceipt(file)
    if (bytes) entries[RECEIPTS_DIR + file] = bytes
  }
  return zipSync(entries)
}

/** O JSON do arquivo (sem ids), montado a partir dos dados do aparelho. */
export function snapshot() {
  const s = db()

  const accountNames = new Map<Id, string>()
  const accounts: FileAccount[] = s.list('account').sort(byCreation).map((a) => {
    accountNames.set(a.id, a.name)
    return {
      name: a.name, type: a.type, initialBalance: fromCents(a.initialBalance), color: a.color, archived: a.archived,
      closingDay: a.closingDay, dueDay: a.dueDay,
    }
  })

  // Pais antes das filhas.
  const cats = s.list('category').sort((a, b) => Number(a.parentId != null) - Number(b.parentId != null) || byCreation(a, b))
  const categoryNames = new Map(cats.map((c) => [c.id, c.name]))
  const categoryPaths = new Map<Id, string>()
  const categories: FileCategory[] = cats.map((c) => {
    const parent = c.parentId == null ? null : (categoryNames.get(c.parentId) ?? null)
    categoryPaths.set(c.id, parent == null ? c.name : parent + SEP + c.name)
    return { name: c.name, type: c.type, color: c.color, icon: c.icon, archived: c.archived, parent }
  })
  const pathOf = (id: Id | null) => (id == null ? null : (categoryPaths.get(id) ?? null))

  const categoryRules = s.list('categoryRule').sort(byCreation)
    .map((r) => ({ keyword: r.keyword, category: pathOf(r.categoryId) }))

  const billRefs = new Map<Id, number>()
  const recurringBills: FileBill[] = s.list('recurringBill').sort(byCreation).map((b, i) => {
    billRefs.set(b.id, i)
    return {
      description: b.description, amount: fromCents(b.amount), type: b.type, frequency: b.frequency,
      startDate: b.startDate, endDate: b.endDate, occurrences: b.occurrences, notes: b.notes, active: b.active,
      startNumber: b.startNumber, matchStrictness: b.matchStrictness,
      account: b.accountId == null ? null : (accountNames.get(b.accountId) ?? null),
      category: pathOf(b.categoryId),
    }
  })

  // Transferências: a perna de saída aponta para a de entrada pela posição no arquivo.
  const transferPeer = new Map<Id, Id>()
  for (const tr of s.list('transfer')) transferPeer.set(tr.fromTransactionId, tr.toTransactionId)
  const txs = s.list('transaction').sort((a, b) => (a.dueDate < b.dueDate ? -1 : a.dueDate > b.dueDate ? 1 : byCreation(a, b)))
  const txRefs = new Map(txs.map((t, i) => [t.id, i]))
  const transactions: FileTransaction[] = txs.map((t) => {
    const out: FileTransaction = {
      description: t.description, amount: fromCents(t.amount), type: t.type, date: t.dueDate, paid: t.paid,
      account: accountNames.get(t.accountId) ?? '', category: pathOf(t.categoryId), notes: t.notes,
      externalId: t.externalId, merchant: t.merchant, receipt: t.receiptFile, receiptAccessKey: t.receiptAccessKey,
      items: [],
    }
    if (t.recurringBillId != null) {
      const ref = billRefs.get(t.recurringBillId)
      if (ref != null) out.recurringBill = ref
      out.installmentNumber = t.installmentNumber
      out.scheduledDate = t.scheduledDate
    }
    if (t.cardAccountId != null) {
      out.cardAccount = accountNames.get(t.cardAccountId) ?? null
      out.invoiceMonth = t.cardInvoiceMonth == null ? null : t.cardInvoiceMonth.slice(0, 7)
    }
    const peer = transferPeer.get(t.id)
    const peerRef = peer == null ? undefined : txRefs.get(peer)
    if (peerRef != null) out.transferTo = peerRef
    out.items = t.items.map((i) => ({
      description: i.description, quantity: i.quantity, unitPrice: fromCents(i.unitPrice), total: fromCents(i.total),
    }))
    return out
  })

  return {
    format: FORMAT, version: VERSION, exportedAt: nowIso(), app: 'ContasLT',
    accounts, categories, categoryRules, recurringBills, transactions,
  }
}

// ---------- Importação (soma ao que existe) ----------

/** Plano da importação: tudo o que será gravado, calculado antes de subir as fotos. */
interface Plan {
  counts: BackupRestored
  accounts: AccountRec[]
  categories: CategoryRec[]
  rules: CategoryRuleRec[]
  bills: RecurringBillRec[]
  transactions: TransactionRec[]
  transfers: TransferRec[]
  /** Lançamento novo → nome da foto no arquivo. */
  receiptOf: Map<TransactionRec, string>
}

export async function importBackup(zip: Uint8Array, io: ImportIo): Promise<BackupRestored> {
  let files: Record<string, Uint8Array>
  try {
    files = unzipSync(zip)
  } catch {
    throw invalidFile()
  }
  const entry = files[JSON_ENTRY]
  if (!entry) throw invalidFile()
  let root: Json
  try {
    root = JSON.parse(strFromU8(entry)) as Json
  } catch {
    throw new BusinessError('Este arquivo não é uma exportação do ContasLT.')
  }
  if (root == null || typeof root !== 'object' || root.format !== FORMAT) {
    throw new BusinessError('Este arquivo não é uma exportação do ContasLT.')
  }
  if (int(root.version, 0) > VERSION) {
    throw new BusinessError('Arquivo de uma versão mais nova do app. Atualize antes de importar.')
  }

  const plan = merge(root)

  // Fotos: só as dos lançamentos que entram; cada uma sobe uma vez e ganha o nome que o servidor der.
  const uploaded = new Map<string, string | null>()
  for (const name of new Set(plan.receiptOf.values())) {
    const bytes = files[RECEIPTS_DIR + name]
    if (!bytes) {
      uploaded.set(name, null)
      continue
    }
    try {
      uploaded.set(name, await io.uploadReceipt(bytes, name))
      plan.counts.receipts++
    } catch (err) {
      console.warn(`[contaslt] importação: não consegui enviar a foto ${name}`, err)
      uploaded.set(name, null)
    }
  }
  for (const [t, name] of plan.receiptOf) t.receiptFile = uploaded.get(name) ?? null

  const s = db()
  for (const a of plan.accounts) s.put('account', a)
  for (const c of plan.categories) s.put('category', c)
  for (const r of plan.rules) s.put('categoryRule', r)
  for (const b of plan.bills) s.put('recurringBill', b)
  for (const t of plan.transactions) s.put('transaction', t)
  for (const tr of plan.transfers) s.put('transfer', tr)
  invalidateEnsured()
  return plan.counts
}

function invalidFile() {
  return new BusinessError(`Arquivo inválido: esperado o zip exportado pelo ContasLT (com ${JSON_ENTRY}).`)
}

function merge(root: Json): Plan {
  const s = db()
  const createdAt = nowIso()
  const counts: BackupRestored = {
    accounts: 0, categories: 0, categoryRules: 0, recurringBills: 0, transactions: 0, skippedTransactions: 0,
    transfers: 0, receipts: 0,
  }
  const plan: Plan = {
    counts, accounts: [], categories: [], rules: [], bills: [], transactions: [], transfers: [], receiptOf: new Map(),
  }

  // Contas pelo nome.
  const accounts = new Map<string, Id>()
  for (const a of s.list('account')) accounts.set(key(a.name), a.id)
  for (const n of array(root.accounts)) {
    const name = text(n, 'name').trim()
    if (accounts.has(key(name))) continue
    const type = oneOf<AccountType>(n.type, ACCOUNT_TYPES, 'CHECKING')
    const a: AccountRec = {
      id: newId(), name, type, initialBalance: cents(n, 'initialBalance', 0), color: text(n, 'color', '#1B5E3F'),
      archived: bool(n, 'archived'), closingDay: intOrNull(n.closingDay), dueDay: intOrNull(n.dueDay), createdAt,
    }
    accounts.set(key(name), a.id)
    plan.accounts.push(a)
  }
  counts.accounts = plan.accounts.length

  // Categorias por tipo + "Pai › Filha" (pais antes das filhas).
  const categories = new Map<string, Id>()
  const existing = s.list('category')
  const existingNames = new Map(existing.map((c) => [c.id, c.name]))
  for (const c of existing) {
    const parent = c.parentId == null ? null : (existingNames.get(c.parentId) ?? null)
    categories.set(categoryKey(c.type, parent, c.name), c.id)
  }
  const fileCategories = array(root.categories)
  for (const childrenPass of [false, true]) {
    for (const n of fileCategories) {
      const parent = text(n, 'parent').trim() ? text(n, 'parent') : null
      if ((parent != null) !== childrenPass) continue
      const k = categoryKey(text(n, 'type'), parent, text(n, 'name'))
      if (categories.has(k)) continue
      const type = oneOf<TransactionType>(n.type, TYPES, 'EXPENSE')
      const c: CategoryRec = {
        id: newId(), name: text(n, 'name').trim(), type, color: text(n, 'color', '#1B5E3F'), icon: text(n, 'icon', 'tag'),
        archived: bool(n, 'archived'),
        parentId: parent == null ? null : (categories.get(categoryKey(text(n, 'type'), null, parent)) ?? null),
        createdAt,
      }
      categories.set(k, c.id)
      plan.categories.push(c)
    }
  }
  counts.categories = plan.categories.length

  // Regras: a palavra-chave é única; as que já existem ficam como estão.
  const keywords = new Set(s.list('categoryRule').map((r) => r.keyword))
  for (const n of array(root.categoryRules)) {
    const category = categoryByPath(categories, n.category)
    const keyword = truncate(normalize(text(n, 'keyword')), 120)
    if (category == null || !keyword || keywords.has(keyword)) continue
    keywords.add(keyword)
    plan.rules.push({ id: newId(), categoryId: category, keyword, createdAt })
  }
  counts.categoryRules = plan.rules.length

  // Recorrentes: igual = mesma descrição, tipo, frequência, data inicial e valor.
  const bills = new Map<string, Id>()
  for (const b of s.list('recurringBill')) bills.set(billKey(b.description, b.type, b.frequency, b.startDate, b.amount), b.id)
  const billIds = new Map<number, Id>()
  array(root.recurringBills).forEach((n, i) => {
    const amount = cents(n, 'amount', 0)
    const startDate = date(n.startDate) ?? ''
    const found = bills.get(billKey(text(n, 'description'), text(n, 'type'), text(n, 'frequency'), startDate, amount))
    if (found != null) {
      billIds.set(i, found)
      return
    }
    const b: RecurringBillRec = {
      id: newId(),
      accountId: accounts.get(key(text(n, 'account'))) ?? null,
      categoryId: categoryByPath(categories, n.category),
      description: text(n, 'description'), amount, type: oneOf<TransactionType>(n.type, TYPES, 'EXPENSE'),
      frequency: oneOf<Frequency>(n.frequency, FREQUENCIES, 'MONTHLY'), startDate, endDate: date(n.endDate),
      occurrences: intOrNull(n.occurrences), notes: textOrNull(n, 'notes'),
      active: typeof n.active === 'boolean' ? n.active : true,
      startNumber: int(n.startNumber, 1), matchStrictness: int(n.matchStrictness, 5), createdAt,
    }
    billIds.set(i, b.id)
    plan.bills.push(b)
  })
  counts.recurringBills = plan.bills.length

  // Lançamentos: pula o que já existe (identificador do extrato na mesma conta, ou mesma conta+data+tipo+valor+
  // descrição, ou a mesma ocorrência de uma recorrente).
  const fingerprints = new Set<string>()
  const externalIds = new Set<string>()
  const occurrences = new Set<string>()
  for (const t of s.list('transaction')) {
    fingerprints.add(fingerprint(t.accountId, t.dueDate, t.type, t.amount, t.description))
    if (t.externalId != null) externalIds.add(`${t.accountId}:${t.externalId}`)
    if (t.recurringBillId != null && t.scheduledDate != null) occurrences.add(`${t.recurringBillId}:${t.scheduledDate}`)
  }
  const all = array(root.transactions)
  const txByRef = new Map<number, TransactionRec>()   // posição no arquivo → lançamento novo
  const usedIds = new Set<Id>()
  all.forEach((n, i) => {
    const account = accounts.get(key(text(n, 'account')))
    if (account == null) throw new BusinessError(`Conta "${text(n, 'account')}" não encontrada no arquivo`)
    const externalId = textOrNull(n, 'externalId')
    const billRef = intOrNull(n.recurringBill)
    const bill = billRef == null ? null : (billIds.get(billRef) ?? null)
    const scheduled = date(n.scheduledDate)
    const dueDate = date(n.date) ?? ''
    const amount = cents(n, 'amount', 0)
    // Mesma avaliação em curto-circuito do servidor: a primeira chave repetida pula sem registrar as demais.
    const duplicate = (externalId != null && !addNew(externalIds, `${account}:${externalId}`))
      || (bill != null && scheduled != null && !addNew(occurrences, `${bill}:${scheduled}`))
      || !addNew(fingerprints, fingerprint(account, dueDate, text(n, 'type'), amount, text(n, 'description')))
    if (duplicate) {
      counts.skippedTransactions++
      return
    }
    const t = emptyTransaction()
    // Ocorrência de recorrente usa o id determinístico (regra + data prevista), para o gerador não criar outra.
    if (bill != null && scheduled != null) {
      const id = occurrenceId(bill, scheduled)
      if (!s.known('transaction', id) && !usedIds.has(id)) t.id = id
    }
    t.accountId = account
    t.categoryId = categoryByPath(categories, n.category)
    t.recurringBillId = bill
    t.description = text(n, 'description')
    t.amount = amount
    t.type = oneOf<TransactionType>(n.type, TYPES, 'EXPENSE')
    t.dueDate = dueDate
    t.paid = bool(n, 'paid')
    t.notes = textOrNull(n, 'notes')
    t.externalId = externalId
    t.installmentNumber = intOrNull(n.installmentNumber)
    t.scheduledDate = scheduled
    t.merchant = textOrNull(n, 'merchant')
    t.receiptAccessKey = textOrNull(n, 'receiptAccessKey')
    if (n.cardAccount != null) t.cardAccountId = accounts.get(key(text(n, 'cardAccount'))) ?? null
    const month = textOrNull(n, 'invoiceMonth')
    t.cardInvoiceMonth = month == null ? null : `${month.slice(0, 7)}-01`
    t.items = array(n.items).map((it): ItemRec => ({
      description: text(it, 'description'),
      quantity: numberOrNull(it.quantity, 'quantity'),
      unitPrice: it.unitPrice == null ? null : cents(it, 'unitPrice', 0),
      total: cents(it, 'total', 0),
    }))
    t.createdAt = createdAt
    usedIds.add(t.id)
    const receipt = textOrNull(n, 'receipt')
    if (receipt != null) plan.receiptOf.set(t, receipt)
    txByRef.set(i, t)
    plan.transactions.push(t)
  })
  counts.transactions = plan.transactions.length

  // Transferências: só quando as duas pernas entram agora.
  all.forEach((n, i) => {
    const toRef = intOrNull(n.transferTo)
    if (toRef == null) return
    const from = txByRef.get(i)
    const to = txByRef.get(toRef)
    if (!from || !to || from === to || from.transferId || to.transferId) return
    const tr: TransferRec = { id: newId(), fromTransactionId: from.id, toTransactionId: to.id, createdAt }
    from.transferId = tr.id
    to.transferId = tr.id
    plan.transfers.push(tr)
  })
  counts.transfers = plan.transfers.length

  return plan
}

// ---------- Chaves de casamento ----------

function key(s: string | null | undefined) {
  return s == null ? '' : s.trim().toLowerCase()
}

function categoryKey(type: string, parent: string | null, name: string) {
  return `${key(type)}|${key(parent)}|${key(name)}`
}

/** "Pai › Filha" ou "Nome" do arquivo → id da categoria (qualquer tipo com esse caminho). */
function categoryByPath(categories: Map<string, Id>, path: unknown): Id | null {
  if (typeof path !== 'string' || !path.trim()) return null
  const sep = path.indexOf(SEP)
  const parent = sep < 0 ? null : path.slice(0, sep)
  const name = sep < 0 ? path : path.slice(sep + SEP.length)
  for (const type of TYPES) {
    const id = categories.get(categoryKey(type, parent, name))
    if (id != null) return id
  }
  return null
}

function billKey(description: string, type: string, frequency: string, startDate: string, amount: Cents) {
  return `${key(description)}|${key(type)}|${key(frequency)}|${startDate}|${amount}`
}

function fingerprint(account: Id, date: string, type: string, amount: Cents, description: string) {
  return `${account}|${date}|${key(type)}|${amount}|${key(description)}`
}

/** Set.add que diz se o valor era novo. */
function addNew(set: Set<string>, value: string) {
  if (set.has(value)) return false
  set.add(value)
  return true
}

// ---------- Leitura tolerante do JSON ----------

const TYPES: TransactionType[] = ['EXPENSE', 'INCOME']
const ACCOUNT_TYPES: AccountType[] = ['CHECKING', 'SAVINGS', 'CREDIT_CARD', 'CASH', 'INVESTMENT', 'OTHER']
const FREQUENCIES: Frequency[] = ['WEEKLY', 'BIWEEKLY', 'MONTHLY', 'BIMONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'YEARLY']

function array(v: unknown): Json[] {
  return Array.isArray(v) ? v.filter((x): x is Json => x != null && typeof x === 'object') : []
}

function text(n: Json, field: string, fallback = ''): string {
  const v = n[field]
  return v == null ? fallback : String(v)
}

function textOrNull(n: Json, field: string): string | null {
  const v = n[field]
  return v == null ? null : String(v)
}

function bool(n: Json, field: string) {
  const v = n[field]
  return v === true || v === 'true'
}

function oneOf<T extends string>(v: unknown, allowed: T[], fallback: T): T {
  return typeof v === 'string' && (allowed as string[]).includes(v) ? (v as T) : fallback
}

function numberOrNull(v: unknown, field: string): number | null {
  if (v == null || v === '') return null
  const x = typeof v === 'number' ? v : Number(v)
  if (!Number.isFinite(x)) throw new BusinessError(`Valor inválido no arquivo (${field}): ${String(v)}`)
  return x
}

/** Valor em reais do arquivo (número ou texto, como o BigDecimal do servidor) → centavos. */
function cents(n: Json, field: string, fallback: Cents): Cents {
  const x = numberOrNull(n[field], field)
  return x == null ? fallback : toCents(x)
}

function int(v: unknown, fallback: number): number {
  const x = typeof v === 'number' ? v : typeof v === 'string' && v.trim() ? Number(v) : NaN
  return Number.isFinite(x) ? Math.trunc(x) : fallback
}

function intOrNull(v: unknown): number | null {
  if (v == null) return null
  const x = int(v, NaN)
  return Number.isNaN(x) ? null : x
}

/** "AAAA-MM-DD" (aceita data-hora, como o servidor: só os 10 primeiros caracteres). */
function date(v: unknown): string | null {
  return typeof v === 'string' && v.trim() ? v.slice(0, 10) : null
}
