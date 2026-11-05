import type { ConfirmRow, ImportBatch, ImportConfirmRequest, ImportPreview, ImportResult, PreviewRow } from '@/api/types'
import type { AccountRec, CategoryRec, Id, ImportBatchRec, ImportSource, TransactionRec } from '@/data/schema'
import { CategorySuggester } from '@/domain/categorySuggester'
import { daysBetween, firstDay, maxDate, minDate, nowIso, plusDays, today, type IsoDate } from '@/domain/dates'
import { parseExcel } from '@/domain/excel'
import { newId } from '@/domain/ids'
import { inferInvoiceMonth } from '@/domain/invoice'
import { fromCents, toCents, type Cents } from '@/domain/money'
import { latin1, parseOfx, ParseError, type ParsedRow } from '@/domain/ofx'
import { normalize, truncate } from '@/domain/text'
import { isCreditCard, requireCard, summarizeInvoice } from './cards'
import { requireCategoryOrNull, saveRule } from './catalog'
import { BusinessError, db, NotFoundError, require, validate } from './context'
import { matchPending, settle } from './recurring'
import { deleteTransaction, emptyTransaction } from './transactions'

/**
 * Importação de extratos OFX e planilhas (porte de ImportService): prévia com duplicados, sugestão de categoria,
 * casamento com lançamentos manuais e vencimentos de recorrentes, e pagamento de fatura de cartão; confirmação
 * cria (ou vincula) os lançamentos num lote, que pode ser desfeito.
 */

const collator = new Intl.Collator('pt-BR')

function requireAccount(id: Id): AccountRec {
  return require(db().get('account', id), 'Conta não encontrada')
}

/** Erro de leitura do arquivo vira mensagem para o usuário (422). */
function parse(source: ImportSource, bytes: Uint8Array): ParsedRow[] {
  try {
    return source === 'OFX' ? parseOfx(bytes) : parseExcel(bytes)
  } catch (e) {
    if (e instanceof ParseError) throw new BusinessError(e.message)
    throw e
  }
}

export function previewImport(accountId: Id, file: { name: string; bytes: Uint8Array }): ImportPreview {
  const account = requireAccount(accountId)
  const bytes = file.bytes
  const filename = file.name ?? 'arquivo'
  const source = detectSource(filename, bytes)
  const parsed = parse(source, bytes).sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0))

  const s = db()
  const categories = s.list('category')
    .sort((a, b) => (a.type < b.type ? -1 : a.type > b.type ? 1 : 0) || collator.compare(a.name, b.name))
  const suggester = new CategorySuggester(categories, s.list('categoryRule'))

  const externalIds = new Set(parsed.map((p) => p.externalId).filter((id): id is string => id != null))
  const txs = s.list('transaction')
  const existingExternal = new Set<string>()
  if (externalIds.size) {
    for (const t of txs) if (t.accountId === account.id && t.externalId != null && externalIds.has(t.externalId)) existingExternal.add(t.externalId)
  }
  const min = parsed.reduce((m, p) => minDate(m, p.date), parsed[0]?.date ?? today())
  const max = parsed.reduce((m, p) => maxDate(m, p.date), parsed[0]?.date ?? today())
  const existingSignatures = new Set(
    txs.filter((t) => t.accountId === account.id && t.dueDate >= min && t.dueDate <= max)
      .map((t) => signature(t.dueDate, t.amount, t.description)),
  )

  const matches = matchExisting(account, parsed, min, max)
  const cards = isCreditCard(account) ? [] : s.list('account')
    .filter((a) => isCreditCard(a) && !a.archived)
    .sort((a, b) => collator.compare(a.name, b.name))
  const current = s.list('transaction')

  const rows: PreviewRow[] = []
  let duplicates = 0
  let index = 0
  for (const p of parsed) {
    const duplicate = (p.externalId != null && existingExternal.has(p.externalId))
      || existingSignatures.has(signature(p.date, p.amount, p.description))
    if (duplicate) duplicates++
    const paymentReceived = !duplicate && isCreditCard(account) && isInvoicePaymentReceived(p)
    const suggestion = suggester.suggest(p.description, p.type, p.categoryHint)
    const m = duplicate ? null : matches.get(p) ?? null
    const mc = m ? s.get('category', m.categoryId) : null
    const card = duplicate || m ? null : invoiceCardFor(p, cards)
    const inv = card ? summarizeInvoice(card, inferInvoiceMonth(card, p.date), current) : null
    rows.push({
      index: index++,
      date: p.date,
      description: p.description,
      amount: fromCents(p.amount),
      type: p.type,
      externalId: p.externalId,
      duplicate,
      suggestedCategoryId: suggestion?.id ?? null,
      suggestedCategoryName: suggestion?.name ?? null,
      suggestedCategoryColor: suggestion?.color ?? null,
      suggestedCategoryIcon: suggestion?.icon ?? null,
      matchTransactionId: m?.id ?? null,
      matchDescription: m?.description ?? null,
      matchDate: m?.dueDate ?? null,
      matchAccountName: m ? (s.get('account', m.accountId)?.name ?? null) : null,
      matchItemCount: m ? m.items.length : 0,
      matchHasReceipt: m != null && m.receiptFile != null,
      matchCategoryId: mc?.id ?? null,
      matchCategoryName: mc?.name ?? null,
      matchRecurring: m != null && m.recurringBillId != null,
      matchAmount: m ? fromCents(m.amount) : null,
      invoiceCardId: inv ? card!.id : null,
      invoiceCardName: inv ? card!.name : null,
      invoiceMonth: inv?.month ?? null,
      invoiceDueDate: inv?.dueDate ?? null,
      invoiceTotal: inv?.purchases ?? null,
      invoicePaid: inv?.paid ?? null,
      invoicePaymentReceived: paymentReceived,
    })
  }
  return { source, filename, accountId: account.id, total: rows.length, duplicates, rows }
}

/** Janela de dias entre a data do extrato e a do lançamento manual para considerá-los a mesma compra. */
export const MATCH_DAYS = 3

const INVOICE_WORDS = ['fatura', 'pgto cartao', 'pagto cartao', 'pagamento cartao', 'pag cartao', 'pagamento de cartao',
  'cartao de credito', 'cartao credito']

const PAYMENT_RECEIVED_WORDS = ['pagamento recebido', 'pagamento efetuado', 'pagto', 'pgto', 'fatura', 'pagamento']

/**
 * No extrato do próprio cartão, a entrada "pagamento recebido" é o outro lado da fatura paga pela conta; não deve
 * virar receita nem estorno de compra.
 */
function isInvoicePaymentReceived(row: ParsedRow) {
  if (row.type !== 'INCOME') return false
  const d = normalize(row.description)
  return PAYMENT_RECEIVED_WORDS.some((w) => d.includes(w))
}

/**
 * Linha do extrato da conta que parece ser o pagamento da fatura de um cartão: despesa cuja descrição fala em
 * fatura/pagamento de cartão ou cita o nome de um cartão cadastrado. O cartão citado pelo nome tem preferência;
 * sem citação, vale o único cartão, ou o primeiro.
 */
function invoiceCardFor(row: ParsedRow, cards: AccountRec[]): AccountRec | null {
  if (!cards.length || row.type !== 'EXPENSE') return null
  const d = ` ${normalize(row.description)} `
  let named: AccountRec | null = null
  for (const c of cards) {
    for (const token of normalize(c.name).split(' ')) {
      if (token.length >= 4 && token !== 'cartao' && token !== 'credito' && d.includes(token)) named = c
    }
  }
  const invoiceWord = INVOICE_WORDS.some((w) => d.includes(w))
  if (named && (invoiceWord || d.includes('pagamento') || d.includes('pgto') || d.includes('pagto'))) return named
  return invoiceWord ? (named ?? cards[0]) : null
}

/**
 * Procura, para cada linha do extrato, um lançamento criado à mão (comprovante, lançamento manual) com o mesmo
 * valor e tipo, até {@link MATCH_DAYS} dias de distância. Cada lançamento casa com no máximo uma linha; em caso de
 * empate vence a mesma conta, a data mais próxima e a descrição mais parecida.
 */
function matchExisting(account: AccountRec, parsed: ParsedRow[], min: IsoDate, max: IsoDate): Map<ParsedRow, TransactionRec> {
  const s = db()
  const from = plusDays(min, -MATCH_DAYS)
  const to = plusDays(max, MATCH_DAYS)
  const creditCard = isCreditCard(account)
  // Compras de cartão só casam com extrato de cartão, e lançamentos de conta só com extrato de conta.
  const candidates = s.list('transaction').filter((t) => {
    if (t.importBatchId || t.externalId != null || t.transferId || t.recurringBillId) return false
    if (t.dueDate < from || t.dueDate > to) return false
    const a = s.get('account', t.accountId)
    return a != null && isCreditCard(a) === creditCard
  })
  const pairs: { row: ParsedRow; tx: TransactionRec; score: number }[] = []
  for (const row of parsed) {
    const rowTokens = tokens(row.description)
    for (const tx of candidates) {
      if (tx.type !== row.type || tx.amount !== row.amount) continue
      const days = Math.abs(daysBetween(tx.dueDate, row.date))
      if (days > MATCH_DAYS) continue
      let score = 100 - days * 15
        + (tx.accountId === account.id ? 30 : 0)
        + (tx.receiptFile != null || tx.items.length > 0 ? 10 : 0)
      const txTokens = tokens(`${tx.description} ${tx.merchant ?? ''}`)
      if ([...rowTokens].some((t) => txTokens.has(t))) score += 25
      pairs.push({ row, tx, score })
    }
  }
  // Vencimentos pendentes de contas recorrentes: nome como chave, valor e data dentro da precisão da regra.
  const occurrences = matchPending(parsed,
    (p) => ({ description: p.description, amount: p.amount, type: p.type, date: p.date }), min, max)
  for (const [row, so] of occurrences) {
    pairs.push({ row, tx: so.occurrence, score: so.score + (so.occurrence.accountId === account.id ? 30 : 0) })
  }
  pairs.sort((a, b) => b.score - a.score)
  const result = new Map<ParsedRow, TransactionRec>()
  const usedTx = new Set<Id>()
  for (const pair of pairs) {
    if (result.has(pair.row) || usedTx.has(pair.tx.id)) continue
    result.set(pair.row, pair.tx)
    usedTx.add(pair.tx.id)
  }
  return result
}

function tokens(text: string): Set<string> {
  const out = new Set<string>()
  for (const t of normalize(text).split(' ')) if (t.length >= 4) out.add(t)
  return out
}

// ---------- Confirmação ----------

/** Mesmas regras do Bean Validation do ImportConfirmRequest/ConfirmRow (mensagens padrão em pt-BR). */
function checkRequest(req: ImportConfirmRequest) {
  const checks: [string, boolean, string][] = [
    ['accountId', req.accountId != null, 'Escolha a conta'],
    ['source', req.source != null, 'não deve ser nulo'],
    ['filename', req.filename == null || req.filename.length <= 255, 'tamanho deve ser entre 0 e 255'],
    ['rows', req.rows != null && req.rows.length > 0, 'Selecione ao menos um lançamento'],
  ]
  ;(req.rows ?? []).forEach((r: ConfirmRow, i) => {
    const f = (name: string) => `rows[${i}].${name}`
    checks.push(
      [f('date'), r.date != null, 'não deve ser nulo'],
      [f('description'), r.description != null && r.description.trim() !== '', 'não deve estar em branco'],
      [f('description'), r.description == null || r.description.length <= 140, 'tamanho deve ser entre 0 e 140'],
      [f('amount'), r.amount != null, 'não deve ser nulo'],
      [f('amount'), r.amount == null || r.amount > 0, 'deve ser maior que 0'],
      [f('type'), r.type != null, 'não deve ser nulo'],
      [f('externalId'), r.externalId == null || r.externalId.length <= 120, 'tamanho deve ser entre 0 e 120'],
    )
  })
  validate(checks)
}

/**
 * Cria os lançamentos do lote (ou vincula linhas a lançamentos existentes). Tudo ou nada, como a transação do
 * servidor: as gravações só acontecem depois que todas as linhas passaram nas regras.
 */
export function confirmImport(req: ImportConfirmRequest): ImportResult {
  checkRequest(req)
  const s = db()
  const account = requireAccount(req.accountId)
  const batch: ImportBatchRec = {
    id: newId(), accountId: account.id, source: req.source, filename: truncate(req.filename, 255),
    importedCount: 0, createdAt: nowIso(),
  }

  const writes: TransactionRec[] = []
  const learned: { keyword: string; categoryId: Id }[] = []
  const seenExternal = new Set<string>()
  const linkedIds = new Set<Id>()
  let imported = 0
  let skipped = 0
  let linked = 0
  let invoicePayments = 0
  for (const row of req.rows) {
    const externalId = row.externalId == null || row.externalId.trim() === '' ? null : row.externalId.trim()
    if (externalId != null && (seenExternal.has(externalId) || existsExternal(account.id, externalId))) {
      skipped++
      continue
    }
    if (externalId != null) seenExternal.add(externalId)
    const amount = toCents(row.amount)
    if (row.linkTransactionId != null) {
      const found = s.get('transaction', row.linkTransactionId)
      if (!found) throw new NotFoundError('Lançamento a vincular não encontrado')
      let existing = structuredClone(found)
      if (existing.importBatchId != null || linkedIds.has(existing.id)) {
        throw new BusinessError(`O lançamento "${existing.description}" já está vinculado a uma importação`)
      }
      linkedIds.add(existing.id)
      if (existing.type !== row.type) {
        throw new BusinessError(`O lançamento "${existing.description}" não é do mesmo tipo da linha do extrato`)
      }
      if (existing.recurringBillId != null) {
        // Vencimento previsto vira o pagamento real: valor e data são os do extrato.
        existing = settle(existing, amount, row.date)
        if (existing.categoryId == null && row.categoryId != null) {
          existing.categoryId = requireCategoryOrNull(row.categoryId)!.id
        }
      } else if (existing.amount !== amount) {
        throw new BusinessError(`O lançamento "${existing.description}" não tem o mesmo valor da linha do extrato`)
      }
      // O lançamento manual (com itens, comprovante, categoria) passa a representar a linha do extrato.
      existing.accountId = account.id
      existing.importBatchId = batch.id
      existing.externalId = externalId
      existing.linkedImport = true
      existing.paid = true
      writes.push(existing)
      linked++
      continue
    }
    const category: CategoryRec | null = requireCategoryOrNull(row.categoryId)
    if (category && category.type !== row.type) {
      throw new BusinessError(`Categoria "${category.name}" não é do tipo do lançamento "${row.description}"`)
    }
    const t = emptyTransaction()
    t.accountId = account.id
    t.categoryId = category?.id ?? null
    t.importBatchId = batch.id
    t.description = row.description.trim()
    t.amount = amount
    t.type = row.type
    t.dueDate = row.date
    t.paid = row.paid == null || row.paid
    t.externalId = externalId
    if (row.invoiceCardId != null) {
      if (row.type !== 'EXPENSE' || isCreditCard(account)) {
        throw new BusinessError(`Pagamento de fatura é uma saída da conta: "${row.description}"`)
      }
      const card = requireCard(row.invoiceCardId)
      t.cardAccountId = card.id
      t.cardInvoiceMonth = firstDay(row.invoiceMonth ? row.invoiceMonth.slice(0, 7) : inferInvoiceMonth(card, row.date))
      invoicePayments++
    }
    writes.push(t)
    imported++

    if (category && row.learnRule === true) learned.push({ keyword: row.description, categoryId: category.id })
  }

  batch.importedCount = imported + linked
  s.put('importBatch', batch)
  for (const t of writes) s.put('transaction', t)
  for (const l of learned) learn(l.categoryId, l.keyword)
  return { batchId: batch.id, imported, skipped, linked, invoicePayments }
}

function existsExternal(accountId: Id, externalId: string) {
  return db().list('transaction').some((t) => t.accountId === accountId && t.externalId === externalId)
}

function learn(categoryId: Id, description: string) {
  const keyword = truncate(normalize(description), 120)
  if (keyword.length < 3) return
  saveRule(keyword, categoryId)
}

// ---------- Histórico ----------

export function importHistory(): ImportBatch[] {
  const s = db()
  return s.list('importBatch')
    .sort((a, b) => (a.createdAt < b.createdAt ? 1 : a.createdAt > b.createdAt ? -1 : a.id < b.id ? 1 : a.id > b.id ? -1 : 0))
    .slice(0, 50)
    .map((b) => ({
      id: b.id, source: b.source, filename: b.filename, accountId: b.accountId,
      accountName: s.get('account', b.accountId)?.name ?? '', importedCount: b.importedCount, createdAt: b.createdAt,
    }))
}

/** Exclui o lote e todos os lançamentos criados por ele. Retorna quantos lançamentos foram removidos. */
export function deleteImport(id: Id): { removed: number } {
  const s = db()
  const batch = require(s.get('importBatch', id), 'Importação não encontrada')
  let removed = 0
  for (const t of s.list('transaction')) {
    if (t.importBatchId !== batch.id) continue
    if (t.linkedImport) {
      // Lançamentos que já existiam e só foram vinculados ao extrato continuam; apenas perdem o vínculo.
      s.put('transaction', { ...t, importBatchId: null, externalId: null, linkedImport: false })
    } else {
      // Como o ON DELETE do banco: a transferência some e a outra perna fica solta.
      deleteTransaction(t.id)
      removed++
    }
  }
  s.remove('importBatch', batch.id)
  return { removed }
}

// ---------- Auxiliares ----------

export function detectSource(filename: string, bytes: Uint8Array): ImportSource {
  const lower = filename.toLowerCase()
  if (lower.endsWith('.ofx') || lower.endsWith('.qfx')) return 'OFX'
  if (lower.endsWith('.xlsx') || lower.endsWith('.xls') || lower.endsWith('.xlsm')) return 'EXCEL'
  const head = latin1(bytes, 400).toUpperCase()
  if (head.includes('OFXHEADER') || head.includes('<OFX')) return 'OFX'
  if (bytes.length > 4 && bytes[0] === 0x50 && bytes[1] === 0x4b) return 'EXCEL'
  throw new BusinessError('Formato não suportado. Envie um arquivo .ofx, .xlsx ou .xls')
}

function signature(date: IsoDate, amount: Cents, description: string) {
  return `${date}|${amount}|${normalize(description)}`
}
