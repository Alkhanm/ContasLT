import { beforeEach, describe, expect, it } from 'vitest'
import type { TransactionInput } from '@/api/types'
import { createAccount, listAccounts } from './catalog'
import { requireCard, summarizeInvoice } from './cards'
import { freshStore } from './testing'
import { createTransaction, listTransactions } from './transactions'

/** Porte de CardInvoiceTest (sem a parte do importador de extrato). */

let checkingId: string
let cardId: string

beforeEach(async () => {
  await freshStore()
  checkingId = createAccount({ name: 'Conta principal', type: 'CHECKING', initialBalance: 0, color: '#1B5E3F' }).id
  // Fecha dia 25, vence dia 5 do mês seguinte.
  const card = createAccount({ name: 'Nubank', type: 'CREDIT_CARD', initialBalance: 0, color: '#820AD1', closingDay: 25, dueDay: 5 })
  expect(card.closingDay).toBe(25)
  cardId = card.id
})

function transaction(accountId: string, description: string, amount: number, type: 'EXPENSE' | 'INCOME', dueDate: string) {
  return createTransaction({ description, amount, type, dueDate, paid: true, notes: null, accountId, categoryId: null }).transaction.id
}

const cardBalance = () => listAccounts().find((a) => a.id === cardId)!.balance
const invoice = (month: string) => summarizeInvoice(requireCard(cardId), month)

describe('cartão de crédito', () => {
  it('compras ficam fora da lista geral e se agrupam pelo período da fatura', () => {
    transaction(cardId, 'Mercado 25/09', 100, 'EXPENSE', '2034-09-25') // fecha em 25/09 → vence 05/10
    transaction(cardId, 'Farmácia 26/09', 50, 'EXPENSE', '2034-09-26') // já é da fatura de novembro
    transaction(cardId, 'Posto 10/09', 200, 'EXPENSE', '2034-09-10')
    transaction(checkingId, 'Padaria', 20, 'EXPENSE', '2034-09-10')

    const all = listTransactions({ month: '2034-09' })
    expect(all.items).toHaveLength(1)
    expect(all.summary.expense).toBe(20)

    const page = listTransactions({ month: '2034-10', accountId: cardId, invoice: true })
    expect(page.items).toHaveLength(2)
    const inv = page.invoice!
    expect(inv.month).toBe('2034-10')
    expect(inv.start).toBe('2034-08-26')
    expect(inv.end).toBe('2034-09-25')
    expect(inv.dueDate).toBe('2034-10-05')
    expect(inv.purchases).toBe(300)
    expect(inv.count).toBe(2)
    expect(inv.paid).toBe(0)
    expect(inv.status).toBe('OPEN') // datas no futuro: ainda não fechou

    const plain = listTransactions({ month: '2034-09', accountId: cardId })
    expect(plain.items).toHaveLength(3)
    expect(plain.invoice).toBeNull()

    // Saldo do cartão = dívida em aberto (nenhuma fatura paga ainda).
    expect(cardBalance()).toBe(-350)
  })

  it('o pagamento da fatura é a despesa e agrega as compras', () => {
    transaction(cardId, 'Mercado', 300, 'EXPENSE', '2034-09-10')
    transaction(cardId, 'Farmácia nov', 50, 'EXPENSE', '2034-09-26') // fatura de novembro, segue em aberto

    const req: TransactionInput = {
      description: 'Fatura Nubank', amount: 500, type: 'EXPENSE', dueDate: '2034-10-05', paid: true, notes: null,
      accountId: checkingId, categoryId: null, invoiceCardId: cardId,
    }
    const dto = createTransaction(req).transaction
    expect(dto.invoiceCardId).toBe(cardId)
    expect(dto.invoiceMonth).toBe('2034-10') // inferido pelo vencimento mais próximo
    expect(dto.invoice!.purchases).toBe(300)
    expect(dto.invoice!.count).toBe(1)
    expect(dto.invoice!.undetailed).toBe(200)
    expect(dto.invoice!.status).toBe('PAID')

    const oct = listTransactions({ month: '2034-10' })
    expect(oct.items).toHaveLength(1)
    expect(oct.items[0].invoice!.count).toBe(1)
    expect(oct.summary.expense).toBe(500)

    expect(cardBalance()).toBe(-50)
    let inv = invoice('2034-10')
    expect(inv.paid).toBe(500)
    expect(inv.paymentTransactionId).toBe(dto.id)

    // Comprovante lido depois entra na fatura e reduz o "não detalhado".
    transaction(cardId, 'Restaurante', 120, 'EXPENSE', '2034-09-20')
    inv = invoice('2034-10')
    expect(inv.purchases).toBe(420)
    expect(inv.undetailed).toBe(80)
    expect(cardBalance()).toBe(-50)

    // Pagar a fatura a partir do próprio cartão não faz sentido.
    expect(() => createTransaction({ ...req, accountId: cardId })).toThrow(expect.objectContaining({ status: 422 }))
  })

  // Equivalente ao fim de statementLineThatPaysTheInvoiceBecomesLinkedExpense, com o pagamento lançado à mão.
  it('pagamento informado com o mês da fatura; estorno abate a compra', () => {
    transaction(cardId, 'Mercado', 300, 'EXPENSE', '2034-09-10')
    transaction(checkingId, 'PADARIA', 35.9, 'EXPENSE', '2034-10-06')
    createTransaction({
      description: 'PAGAMENTO FATURA NUBANK', amount: 480, type: 'EXPENSE', dueDate: '2034-10-05', paid: true, notes: null,
      accountId: checkingId, categoryId: null, invoiceCardId: cardId, invoiceMonth: '2034-10',
    })
    expect(listTransactions({ month: '2034-10', accountId: checkingId }).summary.expense).toBe(515.9)
    let inv = invoice('2034-10')
    expect(inv.paid).toBe(480)
    expect(inv.undetailed).toBe(180)
    expect(inv.status).toBe('PAID')
    expect(cardBalance()).toBe(0)

    // Estorno (receita no cartão) na fatura de novembro: abate a dívida em aberto.
    transaction(cardId, 'Compra nov', 100, 'EXPENSE', '2034-10-10')
    transaction(cardId, 'Estorno', 30, 'INCOME', '2034-10-12')
    expect(cardBalance()).toBe(-70)
    inv = invoice('2034-11')
    expect(inv.purchases).toBe(70)
    expect(inv.count).toBe(2)
    expect(inv.remaining).toBe(70)
    expect(inv.status).toBe('OPEN')

    // Pagamento parcial.
    createTransaction({
      description: 'Parte da fatura', amount: 50, type: 'EXPENSE', dueDate: '2034-11-05', paid: true, notes: null,
      accountId: checkingId, categoryId: null, invoiceCardId: cardId,
    })
    inv = invoice('2034-11')
    expect(inv.status).toBe('PARTIAL')
    expect(inv.remaining).toBe(20)
    expect(cardBalance()).toBe(0)
  })

  it('validações do cartão e do pagamento de fatura', () => {
    expect(() => createAccount({ name: 'X', type: 'CREDIT_CARD', initialBalance: 0, color: '#820AD1', closingDay: 25 }))
      .toThrow(expect.objectContaining({ status: 422 }))
    expect(() => createAccount({ name: 'X', type: 'CREDIT_CARD', initialBalance: 0, color: '#820AD1', closingDay: 5, dueDay: 5 }))
      .toThrow(expect.objectContaining({ status: 422 }))
    // Conta comum perde fechamento/vencimento.
    expect(createAccount({ name: 'Y', type: 'CHECKING', initialBalance: 0, color: '#820AD1', closingDay: 5, dueDay: 10 }).closingDay).toBeNull()
    // Pagamento de fatura precisa ser despesa e o cartão precisa ser cartão.
    const base: TransactionInput = {
      description: 'Fatura', amount: 10, type: 'INCOME', dueDate: '2034-10-05', paid: true, notes: null, accountId: checkingId,
      categoryId: null, invoiceCardId: cardId,
    }
    expect(() => createTransaction(base)).toThrow(expect.objectContaining({ status: 422 }))
    expect(() => createTransaction({ ...base, type: 'EXPENSE', invoiceCardId: checkingId })).toThrow(expect.objectContaining({ status: 422 }))
  })
})
