import { beforeEach, describe, expect, it } from 'vitest'
import type { DayBalance } from '@/api/types'
import { newId } from '@/domain/ids'
import { toCents } from '@/domain/money'
import { createAccount, listAccounts } from './catalog'
import { db } from './context'
import { freshStore } from './testing'
import { createTransaction, emptyTransaction, listTransactions } from './transactions'

/** Porte de DayBalanceTest: saldo ao fim de cada dia e resumo do mês como o extrato. */

beforeEach(async () => { await freshStore() })

function transaction(accountId: string, description: string, amount: number, type: 'EXPENSE' | 'INCOME', dueDate: string, paid: boolean) {
  createTransaction({ description, amount, type, dueDate, paid, notes: null, accountId, categoryId: null })
}

/** Transferência gravada direto no store (o serviço de transferências é testado à parte). */
function transfer(fromAccountId: string, toAccountId: string, amount: number, date: string, description: string) {
  const id = newId()
  const leg = (accountId: string, type: 'EXPENSE' | 'INCOME') => ({
    ...emptyTransaction(), accountId, type, description, amount: toCents(amount), dueDate: date, paid: true, transferId: id,
  })
  const from = leg(fromAccountId, 'EXPENSE')
  const to = leg(toAccountId, 'INCOME')
  db().put('transaction', from)
  db().put('transaction', to)
  db().put('transfer', { id, fromTransactionId: from.id, toTransactionId: to.id, createdAt: from.createdAt })
}

const at = (days: DayBalance[], date: string) => {
  const d = days.find((x) => x.date === date)
  if (!d) throw new Error(`sem saldo para ${date}`)
  return d.balance
}

describe('saldo por dia', () => {
  it('saldo acumulado conta só o pago e passa de um dia para o outro', () => {
    const account = createAccount({ name: 'Conta principal', type: 'CHECKING', initialBalance: 0, color: '#1B5E3F' })
    const initial = account.initialBalance

    transaction(account.id, 'Anterior', 100, 'INCOME', '2032-01-20', true) // mês anterior: entra no ponto de partida
    transaction(account.id, 'Salário', 1000, 'INCOME', '2032-02-05', true)
    transaction(account.id, 'Luz', 200, 'EXPENSE', '2032-02-05', true)
    transaction(account.id, 'Boleto a pagar', 999, 'EXPENSE', '2032-02-10', false) // pendente: não conta
    transaction(account.id, 'Mercado', 50, 'EXPENSE', '2032-02-15', true)

    const days = listTransactions({ month: '2032-02' }).dayBalances
    expect(days).toHaveLength(29) // fevereiro de 2032 é bissexto
    expect(at(days, '2032-02-01')).toBe(initial + 100)
    expect(at(days, '2032-02-05')).toBe(initial + 100 + 1000 - 200)
    expect(at(days, '2032-02-10')).toBe(initial + 900)
    expect(at(days, '2032-02-15')).toBe(initial + 850)
    expect(at(days, '2032-02-29')).toBe(initial + 850)
  })

  it('resumo segue o extrato, com transferências e só o que está pago', () => {
    const pfId = createAccount({ name: 'Conta principal', type: 'CHECKING', initialBalance: 0, color: '#1B5E3F' }).id
    const pjId = createAccount({ name: 'PJ', type: 'CHECKING', initialBalance: 500, color: '#1B5E3F' }).id

    transaction(pjId, 'Cliente jan', 1000, 'INCOME', '2033-01-05', true)
    transaction(pfId, 'Mercado jan', 200, 'EXPENSE', '2033-01-10', true)
    // Fevereiro: pró-labore PJ → PF (transferência), uma despesa paga e uma pendente na PF.
    transfer(pjId, pfId, 800, '2033-02-05', 'Pró-labore')
    transaction(pfId, 'Aluguel fev', 700, 'EXPENSE', '2033-02-10', true)
    transaction(pfId, 'Boleto fev', 50, 'EXPENSE', '2033-02-20', false)

    const page = listTransactions({ month: '2033-02', accountId: pfId })
    const pf = page.summary
    expect(pf.previousBalance).toBe(-200)
    expect(pf.transfer).toBe(800)
    expect(pf.income).toBe(0)
    expect(pf.expense).toBe(700)
    expect(pf.balance).toBe(-100)
    expect(pf.pendingExpense).toBe(50)
    expect(pf.projectedBalance).toBe(-150)
    expect(pf.count).toBe(3)
    expect(at(page.dayBalances, '2033-02-28')).toBe(-100)

    // O resumo é da conta, não da lista filtrada: filtrando "pendentes", só a contagem muda.
    const pending = listTransactions({ month: '2033-02', accountId: pfId, paid: false }).summary
    expect(pending.balance).toBe(-100)
    expect(pending.expense).toBe(700)
    expect(pending.count).toBe(1)

    // Todas as contas: as duas pernas da transferência se anulam; o anterior inclui o saldo inicial da PJ.
    const all = listTransactions({ month: '2033-02' }).summary
    expect(all.previousBalance).toBe(500 + 1000 - 200)
    expect(all.transfer).toBe(0)
    expect(all.expense).toBe(700)
    expect(all.balance).toBe(600)
    expect(all.projectedBalance).toBe(550)

    // Saldo das contas considera as pernas da transferência.
    const balances = Object.fromEntries(listAccounts().map((a) => [a.id, a.balance]))
    expect(balances[pjId]).toBe(500 + 1000 - 800)
    expect(balances[pfId]).toBe(-200 + 800 - 700)
  })

  it('contas arquivadas e cartões ficam fora do recorte de caixa', () => {
    const a = createAccount({ name: 'A', type: 'CHECKING', initialBalance: 100, color: '#1B5E3F' })
    createAccount({ name: 'Velha', type: 'SAVINGS', initialBalance: 1000, color: '#1B5E3F', archived: true })
    const card = createAccount({ name: 'Cartão', type: 'CREDIT_CARD', initialBalance: 0, color: '#1B5E3F', closingDay: 25, dueDay: 5 })
    transaction(card.id, 'Compra', 40, 'EXPENSE', '2033-02-03', true)
    const page = listTransactions({ month: '2033-02' })
    expect(page.summary.previousBalance).toBe(100)
    expect(page.summary.expense).toBe(0)
    expect(at(page.dayBalances, '2033-02-28')).toBe(100)
    // Filtrando uma conta, mesmo arquivada, o recorte é ela.
    expect(listTransactions({ month: '2033-02', accountId: a.id }).summary.previousBalance).toBe(100)
  })
})
