import type {
  DayBalance, Summary, Transaction, TransactionEditScope, TransactionFilter, TransactionInput, TransactionPage,
} from '@/api/types'
import type { AccountRec, Id, ItemRec, TransactionRec } from '@/data/schema'
import { endOfMonth, firstDay, monthOf, nowIso, plusDays, type IsoDate } from '@/domain/dates'
import { newId } from '@/domain/ids'
import { inferInvoiceMonth, invoiceOf } from '@/domain/invoice'
import { fromCents, toCents, toCentsOrNull } from '@/domain/money'
import { blankToNull } from '@/domain/text'
import { idsWithChildren, requireCategoryOrNull } from './catalog'
import { isCreditCard, requireCard, summarizeInvoice } from './cards'
import { BusinessError, db, require, validate } from './context'
import { transactionDto } from './mappers'
import { applyToFollowing, ensureRange, invalidateEnsured } from './recurring'

/** Lançamentos (porte de TransactionService): lista do mês com resumo e saldo por dia, CRUD e escopo de edição. */

interface Range {
  from: IsoDate
  to: IsoDate
  card: AccountRec | null
}

function range(f: TransactionFilter): Range {
  if (f.invoice && f.accountId) {
    const account = require(db().get('account', f.accountId), 'Conta não encontrada')
    if (isCreditCard(account)) {
      const p = invoiceOf(account, f.month)
      return { from: p.start, to: p.end, card: account }
    }
  }
  return { from: firstDay(f.month), to: endOfMonth(f.month), card: null }
}

/** Contas do recorte de caixa: a filtrada, ou todas as ativas de dinheiro (cartão não é caixa). */
function accountScope(f: TransactionFilter): AccountRec[] {
  return db().list('account').filter((a) => (f.accountId ? a.id === f.accountId : !a.archived && !isCreditCard(a)))
}

const byDateDesc = (a: TransactionRec, b: TransactionRec) =>
  (a.dueDate < b.dueDate ? 1 : a.dueDate > b.dueDate ? -1 : 0) || (a.createdAt < b.createdAt ? 1 : a.createdAt > b.createdAt ? -1 : 0)

export function listTransactions(f: TransactionFilter): TransactionPage {
  const r = range(f)
  ensureRange(r.from, r.to)
  const s = db()
  const txs = s.list('transaction')
  const categoryIds = !f.uncategorized && f.categoryId ? new Set(idsWithChildren(f.categoryId)) : null
  const search = f.search?.trim().toLowerCase() || null

  const items = txs.filter((t) => {
    if (t.dueDate < r.from || t.dueDate > r.to) return false
    if (f.accountId) {
      if (t.accountId !== f.accountId) return false
    } else if (isCreditCard(s.get('account', t.accountId))) {
      // Compras de cartão só aparecem na visão do próprio cartão; na lista geral entra a fatura paga.
      return false
    }
    if (f.uncategorized) {
      if (t.categoryId) return false
      // Transferências nunca têm categoria; não são "sem categoria" a resolver.
      if (f.transfers == null && t.transferId) return false
    } else if (categoryIds && (!t.categoryId || !categoryIds.has(t.categoryId))) return false
    if (f.type && t.type !== f.type) return false
    if (f.paid != null && t.paid !== f.paid) return false
    if (f.transfers != null && (f.transfers ? !t.transferId : !!t.transferId)) return false
    if (search && !t.description.toLowerCase().includes(search)) return false
    return true
  }).sort(byDateDesc)

  const scope = accountScope(f)
  const scopeIds = new Set(scope.map((a) => a.id))
  // Saldo anterior ao período: saldo inicial das contas + tudo o que foi pago antes.
  let previous = 0
  if (scope.length) {
    previous = scope.reduce((sum, a) => sum + a.initialBalance, 0)
    for (const t of txs) {
      if (scopeIds.has(t.accountId) && t.paid && t.dueDate < r.from) previous += signed(t)
    }
  }
  return {
    items: items.map((t) => transactionDto(t, { withInvoice: true, txs })),
    summary: summarize(items.length, scopeIds, r, previous, txs),
    dayBalances: dayBalances(scopeIds, r, previous, txs),
    invoice: r.card ? summarizeInvoice(r.card, f.month, txs) : null,
  }
}

const signed = (t: TransactionRec) => (t.type === 'INCOME' ? t.amount : -t.amount)

/** Resumo como o extrato: só o pago entra; o pendente fica à parte e vai para o saldo previsto. */
function summarize(count: number, scopeIds: Set<Id>, r: Range, previous: number, txs: TransactionRec[]): Summary {
  let transfer = 0, income = 0, expense = 0, pendingTransfer = 0, pendingIncome = 0, pendingExpense = 0
  for (const t of txs) {
    if (!scopeIds.has(t.accountId) || t.dueDate < r.from || t.dueDate > r.to) continue
    if (t.transferId) {
      if (t.paid) transfer += signed(t)
      else pendingTransfer += signed(t)
    } else if (t.type === 'INCOME') {
      if (t.paid) income += t.amount
      else pendingIncome += t.amount
    } else if (t.paid) expense += t.amount
    else pendingExpense += t.amount
  }
  const balance = previous + transfer + income - expense
  const projected = balance + pendingTransfer + pendingIncome - pendingExpense
  return {
    previousBalance: fromCents(previous), transfer: fromCents(transfer), income: fromCents(income),
    expense: fromCents(expense), balance: fromCents(balance), pendingTransfer: fromCents(pendingTransfer),
    pendingIncome: fromCents(pendingIncome), pendingExpense: fromCents(pendingExpense),
    projectedBalance: fromCents(projected), count,
  }
}

/** Saldo ao fim de cada dia do período, só com o que está pago, nas contas do recorte. */
function dayBalances(scopeIds: Set<Id>, r: Range, previous: number, txs: TransactionRec[]): DayBalance[] {
  if (!scopeIds.size) return []
  const byDay = new Map<IsoDate, number>()
  for (const t of txs) {
    if (!scopeIds.has(t.accountId) || !t.paid || t.dueDate < r.from || t.dueDate > r.to) continue
    byDay.set(t.dueDate, (byDay.get(t.dueDate) ?? 0) + signed(t))
  }
  const out: DayBalance[] = []
  let running = previous
  for (let day = r.from; day <= r.to; day = plusDays(day, 1)) {
    running += byDay.get(day) ?? 0
    out.push({ date: day, balance: fromCents(running) })
  }
  return out
}

export function requireTransaction(id: Id): TransactionRec {
  return require(db().get('transaction', id), 'Lançamento não encontrado')
}

export function getTransaction(id: Id): Transaction {
  return transactionDto(requireTransaction(id), { withInvoice: true })
}

function checkInput(req: TransactionInput) {
  validate([
    ['description', !!req.description?.trim(), 'Informe a descrição'],
    ['description', (req.description ?? '').trim().length <= 140, 'Descrição muito longa'],
    ['amount', req.amount != null, 'Informe o valor'],
    ['amount', req.amount == null || req.amount > 0, 'O valor deve ser maior que zero'],
    ['type', !!req.type, 'Informe o tipo'],
    ['dueDate', !!req.dueDate, 'Informe a data'],
    ['notes', (req.notes ?? '').length <= 500, 'Observação muito longa'],
    ['accountId', req.accountId != null, 'Escolha a conta'],
    ['merchant', (req.merchant ?? '').length <= 120, 'Estabelecimento muito longo'],
    ['receiptAccessKey', (req.receiptAccessKey ?? '').length <= 44, 'Chave inválida'],
    ['items', (req.items ?? []).length <= 200, 'Máximo de 200 itens'],
    ...(req.items ?? []).map((i, n): [string, boolean, string] => [`items[${n}].description`, !!i.description?.trim(), 'Informe a descrição do item']),
    ...(req.items ?? []).map((i, n): [string, boolean, string] => [`items[${n}].total`, i.total != null, 'Informe o valor do item']),
  ])
}

/** Aplica o formulário no registro. Devolve se a foto do comprovante mudou (e ficou uma). */
function applyInput(t: TransactionRec, req: TransactionInput): boolean {
  checkInput(req)
  const account = require(db().get('account', req.accountId), 'Conta não encontrada')
  const category = requireCategoryOrNull(req.categoryId)
  if (t.transferId && req.type !== t.type) {
    throw new BusinessError('Este lançamento faz parte de uma transferência; desvincule antes de mudar o tipo')
  }
  if (category && category.type !== req.type) {
    throw new BusinessError('A categoria escolhida não é do mesmo tipo do lançamento')
  }
  t.description = req.description.trim()
  t.amount = toCents(req.amount)
  t.type = req.type
  t.dueDate = req.dueDate
  t.paid = !!req.paid
  t.notes = req.notes ?? null
  t.accountId = account.id
  t.categoryId = category?.id ?? null
  applyInvoiceLink(t, req, account)
  t.merchant = blankToNull(req.merchant)
  t.receiptAccessKey = blankToNull(req.receiptAccessKey)
  const receiptFile = blankToNull(req.receiptFile)
  const receiptChanged = t.receiptFile !== receiptFile
  if (receiptChanged) clearReceiptScan(t)
  t.receiptFile = receiptFile
  t.items = (req.items ?? []).map((i): ItemRec => ({
    description: i.description.trim(), quantity: i.quantity ?? null, unitPrice: toCentsOrNull(i.unitPrice), total: toCents(i.total),
  }))
  return receiptChanged && receiptFile != null
}

export function clearReceiptScan(t: TransactionRec) {
  t.receiptScanStatus = null
  t.receiptScanTotal = null
  t.receiptScanDate = null
}

/** Pagamento de fatura: despesa numa conta de dinheiro ligada a um cartão e a um mês de fatura. */
function applyInvoiceLink(t: TransactionRec, req: TransactionInput, account: AccountRec) {
  if (!req.invoiceCardId) {
    t.cardAccountId = null
    t.cardInvoiceMonth = null
    return
  }
  if (req.type !== 'EXPENSE') throw new BusinessError('Pagamento de fatura precisa ser uma despesa')
  if (isCreditCard(account)) {
    throw new BusinessError('O pagamento da fatura sai de uma conta de dinheiro, não do próprio cartão')
  }
  const card = requireCard(req.invoiceCardId)
  const month = req.invoiceMonth ?? inferInvoiceMonth(card, req.dueDate)
  t.cardAccountId = card.id
  t.cardInvoiceMonth = firstDay(monthOf(`${month}-01`))
}

export function emptyTransaction(): TransactionRec {
  return {
    id: newId(), accountId: '', categoryId: null, recurringBillId: null, importBatchId: null, transferId: null,
    description: '', amount: 0, type: 'EXPENSE', dueDate: '', scheduledDate: null, paid: false, notes: null,
    externalId: null, installmentNumber: null, merchant: null, receiptFile: null, receiptAccessKey: null,
    linkedImport: false, receiptScanStatus: null, receiptScanTotal: null, receiptScanDate: null, cardAccountId: null,
    cardInvoiceMonth: null, items: [], createdAt: nowIso(),
  }
}

export interface SaveResult {
  transaction: Transaction
  /** A foto do comprovante foi trocada: a leitura pendente dela deve ser ligada a este lançamento. */
  receiptAttached: TransactionRec | null
}

export function createTransaction(req: TransactionInput): SaveResult {
  const t = emptyTransaction()
  const receiptChanged = applyInput(t, req)
  db().put('transaction', t)
  return { transaction: transactionDto(t, { withInvoice: true }), receiptAttached: receiptChanged ? t : null }
}

export function updateTransaction(id: Id, req: TransactionInput, scope?: TransactionEditScope): SaveResult {
  let t = structuredClone(requireTransaction(id))
  const previousScheduled = t.scheduledDate ?? t.dueDate
  const receiptChanged = applyInput(t, req)
  db().put('transaction', t)
  if (scope === 'FOLLOWING' && t.recurringBillId) t = applyToFollowing(t, previousScheduled)
  return { transaction: transactionDto(t, { withInvoice: true }), receiptAttached: receiptChanged ? t : null }
}

export function setPaid(id: Id, paid: boolean): Transaction {
  const t = { ...requireTransaction(id), paid }
  db().put('transaction', t)
  return transactionDto(t, { withInvoice: true })
}

export function deleteTransaction(id: Id) {
  const s = db()
  const t = requireTransaction(id)
  if (t.transferId) {
    // Desfaz o vínculo antes: a outra perna continua existindo como lançamento comum.
    const tr = s.get('transfer', t.transferId)
    if (tr) {
      for (const legId of [tr.fromTransactionId, tr.toTransactionId]) {
        const leg = s.get('transaction', legId)
        if (leg && leg.id !== id) s.put('transaction', { ...leg, transferId: null })
      }
      s.remove('transfer', tr.id)
    }
  }
  // Vencimento apagado: o gerador volta a conferir o mês (como no servidor, ele reaparece).
  if (t.recurringBillId) invalidateEnsured()
  s.remove('transaction', id)
}

/** Aviso de leitura adiada: usar total/data do cupom, ou manter o lançado. ("Ler de novo" passa pelo servidor.) */
export function resolveReceiptScan(id: Id, action: 'ACCEPT' | 'DISMISS'): Transaction {
  const t = structuredClone(requireTransaction(id))
  if (action === 'ACCEPT') {
    if (t.receiptScanStatus !== 'DIVERGENT') {
      throw new BusinessError('Este lançamento não tem leitura divergente para aplicar')
    }
    if (t.receiptScanTotal != null) t.amount = t.receiptScanTotal
    if (t.receiptScanDate != null) t.dueDate = t.receiptScanDate
  }
  clearReceiptScan(t)
  db().put('transaction', t)
  return transactionDto(t, { withInvoice: true })
}
