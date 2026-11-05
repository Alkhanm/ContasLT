import type { AccountRec } from '@/data/schema'
import { atDay, endOfMonth, firstDay, monthOf, plusDays, plusMonthsYm, toEpochDay, type IsoDate, type YearMonth } from './dates'

/**
 * Recorte de uma fatura de cartão (porte de InvoicePeriod): as compras de `start` a `end` (dia do fechamento)
 * vencem em `dueDate`. A fatura é identificada pelo mês do vencimento. Sem fechamento/vencimento no cartão, vale o
 * mês civil, vencendo no último dia.
 */
export interface InvoicePeriod {
  month: YearMonth
  start: IsoDate
  end: IsoDate
  dueDate: IsoDate
  configured: boolean
}

type Card = Pick<AccountRec, 'closingDay' | 'dueDay'>

export function invoiceOf(card: Card, month: YearMonth): InvoicePeriod {
  if (card.closingDay == null || card.dueDay == null) {
    return { month, start: firstDay(month), end: endOfMonth(month), dueDate: endOfMonth(month), configured: false }
  }
  const end = closingFor(card, month)
  const start = plusDays(closingFor(card, plusMonthsYm(month, -1)), 1)
  return { month, start, end, dueDate: atDay(month, card.dueDay), configured: true }
}

/** Fatura em que cai uma compra feita na data. */
export function invoiceContaining(card: Card, purchaseDate: IsoDate): InvoicePeriod {
  if (card.closingDay == null || card.dueDay == null) return invoiceOf(card, monthOf(purchaseDate))
  const day = Number(purchaseDate.slice(8, 10))
  const closingMonth = day <= card.closingDay ? monthOf(purchaseDate) : plusMonthsYm(monthOf(purchaseDate), 1)
  const due = card.dueDay > card.closingDay ? closingMonth : plusMonthsYm(closingMonth, 1)
  return invoiceOf(card, due)
}

/** Fatura cujo vencimento está mais perto da data de um pagamento. */
export function nearestDue(card: Card, paymentDate: IsoDate): InvoicePeriod {
  const ym = monthOf(paymentDate)
  let best: InvoicePeriod | null = null
  let bestDistance = Number.MAX_SAFE_INTEGER
  for (let i = -1; i <= 1; i++) {
    const p = invoiceOf(card, plusMonthsYm(ym, i))
    const distance = Math.abs(toEpochDay(p.dueDate) - toEpochDay(paymentDate))
    if (distance < bestDistance) {
      bestDistance = distance
      best = p
    }
  }
  return best!
}

/** Mês da fatura que um pagamento quita, quando não informado: o vencimento mais perto da data. */
export function inferInvoiceMonth(card: Card, paymentDate: IsoDate): YearMonth {
  return nearestDue(card, paymentDate).month
}

function closingFor(card: Card, dueMonth: YearMonth): IsoDate {
  const closingMonth = card.dueDay! > card.closingDay! ? dueMonth : plusMonthsYm(dueMonth, -1)
  return atDay(closingMonth, card.closingDay!)
}

const MONTHS = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez']

/** "fatura de out/2026". */
export function invoiceLabel(month: YearMonth) {
  return `fatura de ${MONTHS[Number(month.slice(5, 7)) - 1]}/${month.slice(0, 4)}`
}
