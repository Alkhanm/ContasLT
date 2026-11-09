import { beforeAll, describe, expect, it } from 'vitest'
import { currentMonth, plusMonthsYm } from '@/domain/dates'
import { createAccount, deleteAccount, listAccounts, listCategories, seedDefaults, updateAccount } from './catalog'
import { buildDashboard } from './dashboard'
import { createRecurring, invalidateEnsured, updateRecurring } from './recurring'
import { freshStore } from './testing'
import { createTransaction, deleteTransaction, listTransactions, setPaid } from './transactions'
import type { RecurringBillInput, TransactionInput } from '@/api/types'

/**
 * Porte de ApiFlowTest: as partes de contas, categorias, lançamentos, recorrentes e painel. Os testes rodam em
 * sequência sobre o mesmo store, como os @Order do original. Importação, transferências, comprovantes, backup e
 * autenticação ficam com os testes desses serviços.
 */

let accountId: string
let expenseCategoryId: string
let incomeCategoryId: string
let billId: string

const is422 = expect.objectContaining({ status: 422 })

describe('fluxo da API', () => {
  beforeAll(async () => { await freshStore() })

  it('1. cadastro cria as categorias padrão e a conta principal', () => {
    seedDefaults()
    const accounts = listAccounts()
    expect(accounts).toHaveLength(1)
    expect(accounts[0].name).toBe('Conta principal')
    accountId = accounts[0].id

    const categories = listCategories()
    expect(categories.length).toBeGreaterThan(10)
    expenseCategoryId = categories.find((c) => c.type === 'EXPENSE')!.id
    incomeCategoryId = categories.find((c) => c.type === 'INCOME')!.id
    // Ordem: tipo e depois nome (despesas antes das receitas).
    expect(categories[0].type).toBe('EXPENSE')
    expect(categories[categories.length - 1].type).toBe('INCOME')
  })

  it('3. conta recorrente vira lançamentos mensais', () => {
    const month = currentMonth()
    const start = `${month}-10`
    const req: RecurringBillInput = {
      description: 'Aluguel', amount: 1500, type: 'EXPENSE', frequency: 'MONTHLY', startDate: start, endDate: null,
      occurrences: null, accountId, categoryId: expenseCategoryId, notes: null,
    }
    billId = createRecurring(req).id

    const page = listTransactions({ month })
    expect(page.items).toHaveLength(1)
    expect(page.items[0].recurringBillId).toBe(billId)
    expect(page.summary.pendingExpense).toBe(1500) // ocorrência nasce pendente

    // Próximo mês também gera a ocorrência, de forma idempotente.
    const next = plusMonthsYm(month, 1)
    expect(listTransactions({ month: next }).items).toHaveLength(1)
    expect(listTransactions({ month: next }).items).toHaveLength(1)

    // Alterar o valor atualiza as ocorrências pendentes.
    updateRecurring(billId, { ...req, amount: 1600 })
    const after = listTransactions({ month })
    expect(after.items[0].amount).toBe(1600)
    expect(listTransactions({ month: next }).items[0].amount).toBe(1600)

    // Marcar como pago reflete no saldo da conta.
    setPaid(after.items[0].id, true)
    expect(listAccounts()[0].balance).toBe(-1600)

    // Mudar o calendário recria só os pendentes; o pago fica.
    updateRecurring(billId, { ...req, amount: 1600, startDate: `${month}-15` })
    const moved = listTransactions({ month: next }).items
    expect(moved).toHaveLength(1)
    expect(moved[0].dueDate).toBe(`${next}-15`)
    expect(listTransactions({ month }).items.filter((t) => t.recurringBillId === billId).map((t) => t.paid).sort())
      .toEqual([false, true])
  })

  it('5. painel agrega o mês', () => {
    // Os três lançamentos do extrato de exemplo (o importador é testado à parte).
    const row = (description: string, amount: number, type: 'EXPENSE' | 'INCOME', dueDate: string): TransactionInput => ({
      description, amount, type, dueDate, paid: true, notes: null, accountId,
      categoryId: type === 'INCOME' ? incomeCategoryId : expenseCategoryId,
    })
    createTransaction(row('PAGTO ENEL DISTRIBUICAO', 189.9, 'EXPENSE', '2026-10-03'))
    createTransaction(row('SALARIO ACME LTDA', 4500, 'INCOME', '2026-10-05'))
    createTransaction(row('PADARIA', 52.3, 'EXPENSE', '2026-10-07'))

    const d = buildDashboard('2026-10')
    expect(d.income).toBe(4500)
    expect(d.expense).toBe(242.2)
    expect(d.balance).toBe(4257.8)
    expect(d.paidIncome).toBe(4500)
    expect(d.byCategory.length).toBeGreaterThanOrEqual(1)
    expect(d.byCategory[0].percent).toBe(100)
    expect(d.history).toHaveLength(6)
    expect(d.history[5]).toEqual({ month: '2026-10', income: 4500, expense: 242.2 })
    expect(d.history[0].month).toBe('2026-05')
    expect(d.accounts).toHaveLength(1)
  })

  it('7. erros de validação vêm por campo', () => {
    let error: unknown
    try {
      createTransaction({ description: '', amount: -1 } as TransactionInput)
    } catch (e) {
      error = e
    }
    expect(error).toMatchObject({ status: 400 })
    const errors = (error as { errors: Record<string, string> }).errors
    expect(errors).toHaveProperty('description')
    expect(errors).toHaveProperty('amount')
  })

  it('8. (sem transferências) o aluguel recorrente aparece nos meses seguintes e receitas entram no resumo', () => {
    const pjId = createAccount({ name: 'Conta PJ', type: 'CHECKING', initialBalance: 0, color: '#1B5E3F' }).id
    const month = '2027-03'
    createTransaction({
      description: 'Cliente', amount: 1000, type: 'INCOME', dueDate: `${month}-01`, paid: true, notes: null, accountId: pjId,
      categoryId: incomeCategoryId,
    })
    const page = listTransactions({ month })
    expect(page.items).toHaveLength(2)
    expect(page.summary.income).toBe(1000)
    expect(page.summary.pendingExpense).toBe(1600)
    expect(listAccounts().find((a) => a.id === pjId)!.balance).toBe(1000)

    const dash = buildDashboard(month)
    expect(dash.income).toBe(1000)
    expect(dash.expense).toBe(1600)
    expect(dash.transfers).toBe(0)
  })

  it('contas: excluir só sem lançamentos; arquivar tira da lista', () => {
    expect(() => deleteAccount(accountId)).toThrow(is422)
    const empty = createAccount({ name: 'Vazia', type: 'CASH', initialBalance: 10, color: '#1B5E3F' })
    deleteAccount(empty.id)
    const old = createAccount({ name: 'Antiga', type: 'SAVINGS', initialBalance: 10, color: '#1B5E3F' })
    updateAccount(old.id, { name: 'Antiga', type: 'SAVINGS', initialBalance: 10, color: '#1B5E3F', archived: true })
    expect(listAccounts().some((a) => a.id === old.id)).toBe(false)
    expect(listAccounts(true).at(-1)!.id).toBe(old.id) // arquivadas por último
  })

  it('excluir um vencimento gerado: ele não volta, mesmo quando o gerador confere o mês de novo', () => {
    const next = plusMonthsYm(currentMonth(), 2)
    const occ = listTransactions({ month: next }).items.find((t) => t.recurringBillId === billId)!
    deleteTransaction(occ.id)
    invalidateEnsured()
    const again = listTransactions({ month: next }).items.filter((t) => t.recurringBillId === billId)
    expect(again).toHaveLength(0)
  })
})
