import type { Account, Category, CategoryRule, RecurringBill, Transaction } from '@/api/types'
import type { AccountRec, CategoryRec, CategoryRuleRec, RecurringBillRec, TransactionRec } from '@/data/schema'
import { monthOf, today } from '@/domain/dates'
import { fromCents } from '@/domain/money'
import { nextOnOrAfter } from '@/domain/recurrence'
import { isCreditCard, openDebt, summarizeInvoice } from './cards'
import { db } from './context'

/** Registros do aparelho → DTOs que as telas já usavam (antes vindos da API). Valores em reais. */

export function categoryDto(c: CategoryRec): Category {
  return { id: c.id, name: c.name, type: c.type, color: c.color, icon: c.icon, archived: c.archived, parentId: c.parentId }
}

/** Movimento pago líquido por conta (receitas − despesas), de uma vez para todas. */
export function paidMovementsByAccount(txs = db().list('transaction')) {
  const out = new Map<string, number>()
  for (const t of txs) {
    if (!t.paid) continue
    out.set(t.accountId, (out.get(t.accountId) ?? 0) + (t.type === 'INCOME' ? t.amount : -t.amount))
  }
  return out
}

/**
 * Saldo: inicial + movimentos pagos (inclusive pernas de transferência). Cartão de crédito: saldo inicial menos a
 * dívida em aberto (compras cuja fatura ainda não teve pagamento registrado).
 */
export function accountBalance(a: AccountRec, movements: Map<string, number>, txs = db().list('transaction')) {
  return isCreditCard(a) ? a.initialBalance - openDebt(a, txs) : a.initialBalance + (movements.get(a.id) ?? 0)
}

export function accountDto(a: AccountRec, balanceCents: number): Account {
  return {
    id: a.id, name: a.name, type: a.type, initialBalance: fromCents(a.initialBalance), color: a.color,
    archived: a.archived, balance: fromCents(balanceCents), closingDay: a.closingDay, dueDay: a.dueDay,
  }
}

export function ruleDto(r: CategoryRuleRec): CategoryRule | null {
  const c = db().get('category', r.categoryId)
  if (!c) return null
  return { id: r.id, keyword: r.keyword, categoryId: c.id, categoryName: c.name, categoryColor: c.color, categoryIcon: c.icon }
}

export function recurringDto(b: RecurringBillRec): RecurringBill {
  const a = db().get('account', b.accountId)
  const c = db().get('category', b.categoryId)
  return {
    id: b.id, description: b.description, amount: fromCents(b.amount), type: b.type, frequency: b.frequency,
    startDate: b.startDate, endDate: b.endDate, occurrences: b.occurrences,
    accountId: a?.id ?? null, accountName: a?.name ?? null,
    categoryId: c?.id ?? null, categoryName: c?.name ?? null, categoryColor: c?.color ?? null, categoryIcon: c?.icon ?? null,
    notes: b.notes, active: b.active, nextDueDate: b.active ? nextOnOrAfter(b, today()) : null,
    matchStrictness: b.matchStrictness,
  }
}

/** A outra perna de uma transferência. */
export function transferPeer(t: TransactionRec): TransactionRec | null {
  if (!t.transferId) return null
  const tr = db().get('transfer', t.transferId)
  if (!tr) return null
  const peerId = tr.fromTransactionId === t.id ? tr.toTransactionId : tr.fromTransactionId
  return db().get('transaction', peerId)
}

/** `withInvoice`: pagamento de fatura leva o resumo da fatura agregada (lista e formulário usam). */
export function transactionDto(t: TransactionRec, opts: { withInvoice?: boolean; txs?: TransactionRec[] } = {}): Transaction {
  const s = db()
  const a = s.get('account', t.accountId)
  const c = s.get('category', t.categoryId)
  const r = s.get('recurringBill', t.recurringBillId)
  const transfer = t.transferId ? s.get('transfer', t.transferId) : null
  const peer = transfer ? transferPeer(t) : null
  const peerAccount = peer ? s.get('account', peer.accountId) : null
  const card = s.get('account', t.cardAccountId)
  const invoiceMonth = t.cardInvoiceMonth ? monthOf(t.cardInvoiceMonth) : null
  return {
    id: t.id,
    description: t.description,
    amount: fromCents(t.amount),
    type: t.type,
    dueDate: t.dueDate,
    paid: t.paid,
    notes: t.notes,
    accountId: t.accountId,
    accountName: a?.name ?? '',
    categoryId: c?.id ?? null,
    categoryName: c?.name ?? null,
    categoryColor: c?.color ?? null,
    categoryIcon: c?.icon ?? null,
    recurringBillId: r?.id ?? null,
    installmentNumber: t.installmentNumber,
    installmentTotal: r?.occurrences ?? null,
    scheduledDate: r ? t.scheduledDate : null,
    expectedAmount: r ? fromCents(r.amount) : null,
    externalId: t.externalId,
    transferId: transfer?.id ?? null,
    transferPeerTransactionId: peer?.id ?? null,
    transferPeerAccountId: peerAccount?.id ?? null,
    transferPeerAccountName: peerAccount?.name ?? null,
    merchant: t.merchant,
    receiptFile: t.receiptFile,
    receiptAccessKey: t.receiptAccessKey,
    importBatchId: t.importBatchId,
    items: t.items.map((i) => ({
      id: null, description: i.description, quantity: i.quantity, unitPrice: fromCents(i.unitPrice), total: fromCents(i.total),
    })),
    invoiceCardId: card?.id ?? null,
    invoiceCardName: card?.name ?? null,
    invoiceMonth,
    invoice: opts.withInvoice && card && invoiceMonth ? summarizeInvoice(card, invoiceMonth, opts.txs) : null,
    receiptScanStatus: t.receiptScanStatus,
    receiptScanTotal: fromCents(t.receiptScanTotal),
    receiptScanDate: t.receiptScanDate,
  }
}
