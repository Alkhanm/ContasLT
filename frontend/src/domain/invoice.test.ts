import { describe, expect, it } from 'vitest'
import { inferInvoiceMonth, invoiceContaining, invoiceLabel, invoiceOf, nearestDue } from './invoice'

/** Períodos de fatura (InvoicePeriod), como usados em CardInvoiceTest. */

// Fecha dia 25, vence dia 5 do mês seguinte (o cartão do CardInvoiceTest).
const nubank = { closingDay: 25, dueDay: 5 }

describe('InvoicePeriod', () => {
  it('fatura de outubro: compras de 26/08 a 25/09, vence em 05/10', () => {
    expect(invoiceOf(nubank, '2034-10')).toEqual({
      month: '2034-10', start: '2034-08-26', end: '2034-09-25', dueDate: '2034-10-05', configured: true,
    })
  })

  it('compra no dia do fechamento fica na fatura; no dia seguinte vai para a próxima', () => {
    expect(invoiceContaining(nubank, '2034-09-25').month).toBe('2034-10')
    expect(invoiceContaining(nubank, '2034-09-26').month).toBe('2034-11')
    expect(invoiceContaining(nubank, '2034-09-10').month).toBe('2034-10')
  })

  it('vencimento depois do fechamento no mesmo mês', () => {
    const card = { closingDay: 3, dueDay: 10 }
    expect(invoiceOf(card, '2034-10')).toMatchObject({ start: '2034-09-04', end: '2034-10-03', dueDate: '2034-10-10' })
    expect(invoiceContaining(card, '2034-10-03').month).toBe('2034-10')
    expect(invoiceContaining(card, '2034-10-04').month).toBe('2034-11')
  })

  it('sem fechamento/vencimento vale o mês civil, vencendo no último dia', () => {
    const card = { closingDay: null, dueDay: null }
    expect(invoiceOf(card, '2032-02')).toEqual({
      month: '2032-02', start: '2032-02-01', end: '2032-02-29', dueDate: '2032-02-29', configured: false,
    })
    expect(invoiceContaining(card, '2032-02-15').month).toBe('2032-02')
  })

  it('pagamento quita a fatura com vencimento mais próximo', () => {
    expect(inferInvoiceMonth(nubank, '2034-10-05')).toBe('2034-10')
    expect(inferInvoiceMonth(nubank, '2034-10-01')).toBe('2034-10')
    expect(inferInvoiceMonth(nubank, '2034-10-28')).toBe('2034-11')
    expect(nearestDue(nubank, '2034-09-30').dueDate).toBe('2034-10-05')
  })

  it('rótulo da fatura', () => {
    expect(invoiceLabel('2026-10')).toBe('fatura de out/2026')
  })
})
