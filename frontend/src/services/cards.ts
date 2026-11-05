import type { CardInvoice, InvoiceStatus } from '@/api/types'
import type { AccountRec, Id, TransactionRec } from '@/data/schema'
import { firstDay, monthOf, today, type YearMonth } from '@/domain/dates'
import { invoiceOf } from '@/domain/invoice'
import { fromCents } from '@/domain/money'
import { BusinessError, db, require } from './context'

/**
 * Faturas de cartão (porte de CardInvoiceService). Cada compra é um lançamento no cartão, na data da compra, mas
 * compras ficam fora das listas e dos totais: o que conta como despesa é a fatura paga, um lançamento comum na conta
 * que pagou, ligado ao cartão e ao mês da fatura (`cardAccountId` + `cardInvoiceMonth`).
 */

export const isCreditCard = (a: Pick<AccountRec, 'type'> | null | undefined) => a?.type === 'CREDIT_CARD'

export function requireCard(cardId: Id): AccountRec {
  const card = require(db().get('account', cardId), 'Conta não encontrada')
  if (!isCreditCard(card)) throw new BusinessError(`A conta "${card.name}" não é um cartão de crédito`)
  return card
}

/** Compras do cartão (sem pernas de transferência) num período: despesas somam, estornos (receitas) abatem. */
function purchasesIn(cardId: Id, from: string, to: string, txs: TransactionRec[]) {
  let total = 0
  let count = 0
  for (const t of txs) {
    if (t.accountId !== cardId || t.transferId || t.dueDate < from || t.dueDate > to) continue
    total += t.type === 'EXPENSE' ? t.amount : -t.amount
    count++
  }
  return { total, count }
}

/** Pagamentos registrados de uma fatura: despesas (em qualquer conta) ligadas ao cartão e ao mês. */
export function invoicePayments(cardId: Id, month: YearMonth, txs = db().list('transaction')) {
  const m = firstDay(month)
  return txs.filter((t) => t.cardAccountId === cardId && t.cardInvoiceMonth === m)
}

export function summarizeInvoice(card: AccountRec, month: YearMonth, txs = db().list('transaction')): CardInvoice {
  const p = invoiceOf(card, month)
  const { total: purchases, count } = purchasesIn(card.id, p.start, p.end, txs)
  const payments = invoicePayments(card.id, month, txs)
  const paid = payments.reduce((s, t) => s + t.amount, 0)
  const undetailed = Math.max(0, paid - purchases)
  const remaining = Math.max(0, purchases - paid)
  let status: InvoiceStatus
  if (paid > 0 && remaining === 0) status = 'PAID'
  else if (paid > 0) status = 'PARTIAL'
  else if (today() <= p.end) status = 'OPEN'
  else status = 'CLOSED'
  return {
    accountId: card.id,
    accountName: card.name,
    month,
    start: p.start,
    end: p.end,
    dueDate: p.dueDate,
    configured: p.configured,
    purchases: fromCents(purchases),
    count,
    paid: fromCents(paid),
    undetailed: fromCents(undetailed),
    remaining: fromCents(remaining),
    status,
    paymentTransactionId: payments[0]?.id ?? null,
  }
}

/**
 * Saldo devedor do cartão: compras (líquidas) fora dos períodos das faturas que já têm pagamento registrado.
 */
export function openDebt(card: AccountRec, txs = db().list('transaction')): number {
  const paidPeriods = [...new Set(txs.filter((t) => t.cardAccountId === card.id && t.cardInvoiceMonth).map((t) => monthOf(t.cardInvoiceMonth!)))]
    .map((m) => invoiceOf(card, m))
  let debt = 0
  for (const t of txs) {
    if (t.accountId !== card.id || t.transferId) continue
    if (paidPeriods.some((p) => t.dueDate >= p.start && t.dueDate <= p.end)) continue
    debt += t.type === 'EXPENSE' ? t.amount : -t.amount
  }
  return debt
}
