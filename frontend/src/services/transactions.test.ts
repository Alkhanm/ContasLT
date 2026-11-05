import { beforeEach, describe, expect, it } from 'vitest'
import { basicCatalog, freshStore } from './testing'
import { createTransaction, listTransactions } from './transactions'
import { createRecurring } from './recurring'
import { buildDashboard } from './dashboard'

describe('lançamentos', () => {
  beforeEach(async () => { await freshStore() })

  it('lista do mês com resumo, saldo por dia e vencimentos de recorrente gerados', () => {
    const { account, expense, income } = basicCatalog()
    createTransaction({ description: 'Salário', amount: 5000, type: 'INCOME', dueDate: '2026-03-05', paid: true, notes: null, accountId: account.id, categoryId: income.id })
    createTransaction({ description: 'Feira', amount: 120.5, type: 'EXPENSE', dueDate: '2026-03-06', paid: true, notes: null, accountId: account.id, categoryId: expense.id })
    createRecurring({ description: 'Aluguel', amount: 1500, type: 'EXPENSE', frequency: 'MONTHLY', startDate: '2026-01-10', endDate: null, occurrences: null, accountId: account.id, categoryId: expense.id, notes: null })

    const page = listTransactions({ month: '2026-03' })
    expect(page.items.map((t) => t.description)).toEqual(['Aluguel', 'Feira', 'Salário'])
    expect(page.items[0].paid).toBe(false)
    expect(page.items[0].scheduledDate).toBe('2026-03-10')
    expect(page.summary.income).toBe(5000)
    expect(page.summary.expense).toBe(120.5)
    expect(page.summary.balance).toBe(4879.5)
    expect(page.summary.pendingExpense).toBe(1500)
    expect(page.summary.projectedBalance).toBe(3379.5)
    expect(page.dayBalances).toHaveLength(31)
    expect(page.dayBalances[5]).toEqual({ date: '2026-03-06', balance: 4879.5 })

    // Ver o mês de novo não duplica o vencimento.
    expect(listTransactions({ month: '2026-03' }).items).toHaveLength(3)

    const dash = buildDashboard('2026-03')
    expect(dash.expense).toBe(1620.5)
    expect(dash.byCategory[0].name).toBe('Mercado')
    expect(dash.history).toHaveLength(6)
  })
})
