import { beforeEach, describe, expect, it } from 'vitest'
import type { Id, TransactionItemInput } from '@/api/types'
import { createAccount, createCategory } from './catalog'
import { createRecurring } from './recurring'
import { categoryReport, itemReport } from './reports'
import { basicCatalog, freshStore } from './testing'
import { createTransaction } from './transactions'
import { createTransfer } from './transfers'

describe('relatórios', () => {
  let accountId: Id

  beforeEach(async () => {
    await freshStore()
    accountId = basicCatalog().account.id
  })

  const category = (name: string, parentId: Id | null) =>
    createCategory({ name, type: 'EXPENSE', color: '#3366FF', icon: 'tag', parentId }).id

  const item = (description: string, quantity: number, unitPrice: number | null, total: number): TransactionItemInput =>
    ({ description, quantity, unitPrice, total })

  const expense = (description: string, amount: number, date: string, categoryId: Id | null, items: TransactionItemInput[] = [], account = accountId) =>
    createTransaction({
      description, amount, type: 'EXPENSE', dueDate: date, paid: true, notes: null, accountId: account, categoryId, items,
      merchant: items.length ? 'Mercadinho' : null,
    }).transaction

  const income = (description: string, amount: number, date: string) =>
    createTransaction({ description, amount, type: 'INCOME', dueDate: date, paid: true, notes: null, accountId, categoryId: null })

  it('categorias agrupam subcategorias sob a categoria pai, com mês anterior e histórico', () => {
    const mercado = category('Mercado', null)
    const padaria = category('Padaria', mercado)
    const lazer = category('Lazer', null)

    expense('Compra do mês', 300, '2034-05-02', mercado)
    expense('Pão', 50, '2034-05-10', padaria)
    expense('Cinema', 100, '2034-05-15', lazer)
    expense('Sem rumo', 40, '2034-05-20', null)
    expense('Mercado de abril', 200, '2034-04-05', mercado)
    expense('Mercado de janeiro', 120, '2034-01-05', mercado)
    income('Salário', 5000, '2034-05-05')
    // Fora do relatório: compra no cartão e transferência entre contas.
    const card = createAccount({ name: 'Cartão', type: 'CREDIT_CARD', initialBalance: 0, color: '#000000', closingDay: 5, dueDay: 12 })
    expense('No cartão', 999, '2034-05-03', mercado, [], card.id)
    const savings = createAccount({ name: 'Poupança', type: 'SAVINGS', initialBalance: 0, color: '#000000' })
    createTransfer({ fromAccountId: accountId, toAccountId: savings.id, amount: 777, date: '2034-05-04', description: null, paid: true })

    const report = categoryReport('2034-05')
    expect(report.month).toBe('2034-05')
    expect(report.total).toBe(490)
    expect(report.previousTotal).toBe(200)
    expect(report.count).toBe(4)
    const items = report.items
    expect(items).toHaveLength(3)

    const first = items[0]
    expect(first.name).toBe('Mercado')
    expect(first.categoryId).toBe(mercado)
    expect(first.total).toBe(350)
    expect(first.count).toBe(2)
    expect(first.previousTotal).toBe(200)
    expect(first.percent).toBe(71.4)
    // Subcategoria com gasto: Padaria e "Geral" (o que foi lançado direto em Mercado).
    expect(first.children).toHaveLength(2)
    expect(first.children[0].name).toBe('Geral')
    expect(first.children[0].categoryId).toBe(mercado)
    expect(first.children[0].total).toBe(300)
    expect(first.children[0].percent).toBe(85.7)
    expect(first.children[0].previousTotal).toBeNull()
    expect(first.children[1].name).toBe('Padaria')
    expect(first.children[1].categoryId).toBe(padaria)
    expect(first.children[1].total).toBe(50)
    // Seis meses: dezembro de 2033 a maio de 2034.
    const history = first.history
    expect(history).toHaveLength(6)
    expect(history[0].month).toBe('2033-12')
    expect(history[1].total).toBe(120)
    expect(history[4].total).toBe(200)
    expect(history[5].total).toBe(350)

    expect(items[1].name).toBe('Lazer')
    expect(items[1].children).toHaveLength(0) // sem subcategoria: não desdobra
    expect(items[2].name).toBe('Sem categoria')
    expect(items[2].categoryId).toBeNull()
    expect(items[2].color).toBe('#9CA3AF')
    expect(items[2].icon).toBe('circle-dashed')
  })

  it('categorias incluem os vencimentos de recorrente do mês (gerados na consulta)', () => {
    const casa = category('Casa', null)
    createRecurring({ description: 'Aluguel', amount: 1500, type: 'EXPENSE', frequency: 'MONTHLY', startDate: '2034-01-10', endDate: null, occurrences: null, accountId, categoryId: casa, notes: null })
    const report = categoryReport('2034-03')
    expect(report.total).toBe(1500)
    expect(report.previousTotal).toBe(1500)
    expect(report.items[0].history.map((h) => h.total)).toEqual([0, 0, 0, 1500, 1500, 1500])
  })

  it('itens agrupam linhas de comprovante pelo nome, com meses e histórico de preço', () => {
    const mercado = category('Mercado', null)
    const padaria = category('Padaria', mercado)

    const first = expense('Supermercado', 20, '2034-02-03', mercado, [
      item('Leite Integral 1L', 2, 4.5, 9.0),
      item('Arroz 5kg', 1, null, 11.0),
    ])
    expense('Supermercado', 25.4, '2034-03-07', padaria, [
      item('LEITE INTEGRAL 1L', 3, 5.2, 15.6),
      item('Pão francês', 0.5, 19.6, 9.8),
    ])
    expense('Fora do período', 9, '2033-06-01', mercado, [item('Leite integral 1l', 1, 3.9, 3.9)])

    const report = itemReport({ from: '2034-01', to: '2034-03' })
    expect(report.from).toBe('2034-01')
    expect(report.to).toBe('2034-03')
    expect(report.total).toBe(45.4)
    expect(report.count).toBe(4)
    const items = report.items
    expect(items).toHaveLength(3)

    const leite = items[0]
    expect(leite.key).toBe('leite integral l')
    expect(leite.description).toBe('LEITE INTEGRAL 1L') // nome da compra mais recente
    expect(leite.categoryName).toBe('Mercado › Padaria') // categoria da compra mais recente
    expect(leite.categoryId).toBe(padaria)
    expect(leite.count).toBe(2)
    expect(leite.quantity).toBe(5)
    expect(leite.total).toBe(24.6)
    expect(leite.firstUnitPrice).toBe(4.5)
    expect(leite.lastUnitPrice).toBe(5.2)
    expect(leite.minUnitPrice).toBe(4.5)
    expect(leite.maxUnitPrice).toBe(5.2)
    expect(leite.months).toHaveLength(2)
    expect(leite.months[0].month).toBe('2034-02')
    expect(leite.months[1].total).toBe(15.6)
    expect(leite.purchases).toHaveLength(2)
    expect(leite.purchases[0].date).toBe('2034-02-03')
    expect(leite.purchases[0].transactionId).toBe(first.id)
    expect(leite.purchases[0].merchant).toBe('Mercadinho')

    // Sem preço unitário: total ÷ quantidade.
    const arroz = items[1]
    expect(arroz.description).toBe('Arroz 5kg')
    expect(arroz.lastUnitPrice).toBe(11)

    // Filtro pela categoria pai inclui a subcategoria; pela subcategoria, só ela. Busca pelo nome.
    expect(itemReport({ from: '2034-01', to: '2034-03', categoryId: mercado }).items).toHaveLength(3)
    const bySub = itemReport({ from: '2034-01', to: '2034-03', categoryId: padaria })
    expect(bySub.items).toHaveLength(2)
    expect(bySub.items[0].count).toBe(1)
    const bySearch = itemReport({ from: '2034-01', to: '2034-03', search: 'pão' })
    expect(bySearch.items).toHaveLength(1)
    expect(bySearch.items[0].description).toBe('Pão francês')

    expect(() => itemReport({ from: '2034-04', to: '2034-03' })).toThrow('O mês inicial não pode ser depois do final')
  })

  it('itens: preço unitário calculado quando falta e período padrão de 12 meses', () => {
    expense('Feira', 10, '2034-02-03', null, [item('Banana', 3, null, 10)])
    const r = itemReport({ from: '2034-02', to: '2034-02' })
    expect(r.items[0].lastUnitPrice).toBe(3.33)
    expect(r.items[0].categoryName).toBeNull()
    expect(r.items[0].color).toBe('#9CA3AF')
    const def = itemReport({ to: '2034-12' })
    expect(def.from).toBe('2034-01')
    expect(def.items).toHaveLength(1)
  })
})
