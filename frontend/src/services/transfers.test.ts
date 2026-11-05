import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TransactionInput } from '@/api/types'
import { accountById, createAccount } from './catalog'
import { db } from './context'
import { buildDashboard } from './dashboard'
import { createRecurring } from './recurring'
import { basicCatalog, freshStore } from './testing'
import { createTransaction, deleteTransaction, getTransaction, listTransactions } from './transactions'
import { createTransfer, forgetRejections, linkTransfer, rejectTransfer, transferSuggestions, unlinkTransfer } from './transfers'

const tx = (req: Partial<TransactionInput> & Pick<TransactionInput, 'description' | 'amount' | 'type' | 'dueDate' | 'accountId'>) =>
  createTransaction({ paid: true, notes: null, categoryId: null, ...req }).transaction

describe('transferências', () => {
  beforeEach(async () => {
    // Só o relógio de datas: o cruzamento olha 18 meses para trás a partir de hoje.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2027, 3, 15, 12))
    await freshStore()
  })
  afterEach(() => { vi.useRealTimers() })

  it('entre contas são neutras: criação direta, cruzamento automático, vínculo, desvínculo e exclusão de perna', () => {
    const { account, expense, income } = basicCatalog()
    const pj = createAccount({ name: 'Conta PJ', type: 'CHECKING', initialBalance: 0, color: '#123456' })
    const month = '2027-03'
    createRecurring({ description: 'Aluguel', amount: 1600, type: 'EXPENSE', frequency: 'MONTHLY', startDate: '2027-01-10', endDate: null, occurrences: null, accountId: account.id, categoryId: expense.id, notes: null })
    tx({ description: 'Cliente', amount: 1000, type: 'INCOME', dueDate: `${month}-01`, accountId: pj.id, categoryId: income.id })

    // 1) Transferência criada diretamente: duas pernas vinculadas.
    const tr = createTransfer({ fromAccountId: pj.id, toAccountId: account.id, amount: 600, date: `${month}-05`, description: 'Pró-labore', paid: true })
    expect(tr.from.type).toBe('EXPENSE')
    expect(tr.to.type).toBe('INCOME')
    expect(tr.to.transferPeerAccountId).toBe(pj.id)
    expect(tr.from.transferId).toBe(tr.id)
    expect(tr.to.transferId).toBe(tr.id)
    expect(tr.from.description).toBe('Pró-labore')

    const page = listTransactions({ month })
    expect(page.items).toHaveLength(4)
    expect(page.summary.income).toBe(1000)
    expect(page.summary.pendingExpense).toBe(1600)
    expect(page.summary.transfer).toBe(0) // 600 saiu, 600 entrou
    // O saldo de cada conta considera as pernas: a PJ recebeu 1000 e transferiu 600.
    expect(accountById(pj.id).balance).toBe(400)
    const pjDay = listTransactions({ month, accountId: pj.id }).dayBalances.find((d) => d.date === `${month}-05`)
    expect(pjDay?.balance).toBe(400)
    // Filtro "só transferências".
    expect(listTransactions({ month, transfers: true }).items).toHaveLength(2)

    // 2) Cruzamento automático: saída numa conta e entrada de mesmo valor na outra, 1 dia depois.
    const out = tx({ description: 'Transferência enviada pelo Pix', amount: 250, type: 'EXPENSE', dueDate: `${month}-10`, accountId: pj.id })
    const into = tx({ description: 'Transferência recebida', amount: 250, type: 'INCOME', dueDate: `${month}-11`, accountId: account.id })
    // Ruído: receita de mesmo valor na MESMA conta da saída não pode ser sugerida.
    tx({ description: 'Reembolso', amount: 250, type: 'INCOME', dueDate: `${month}-10`, accountId: pj.id })

    const suggestions = transferSuggestions().items
    expect(suggestions).toHaveLength(1)
    expect(suggestions[0].from.id).toBe(out.id)
    expect(suggestions[0].to.id).toBe(into.id)
    expect(suggestions[0].daysApart).toBe(1)

    const linked = linkTransfer(into.id, out.id)
    expect(linked.from.id).toBe(out.id)
    expect(linked.to.transferPeerTransactionId).toBe(out.id)

    let dash = buildDashboard(month)
    expect(dash.income).toBe(1250)
    expect(dash.expense).toBe(1600)
    expect(dash.transfers).toBe(850)
    expect(transferSuggestions().items).toHaveLength(0)

    // Vincular de novo é recusado; valores diferentes também.
    expect(() => linkTransfer(into.id, out.id)).toThrow('Um dos lançamentos já faz parte de outra transferência')
    const other = tx({ description: 'Outro', amount: 99, type: 'INCOME', dueDate: `${month}-12`, accountId: account.id })
    const reembolso = listTransactions({ month }).items.find((t) => t.description === 'Reembolso')!
    expect(() => linkTransfer(other.id, tr.from.id)).toThrow('Um dos lançamentos já faz parte de outra transferência')
    expect(() => linkTransfer(other.id, out.id)).toThrow()
    expect(() => linkTransfer(reembolso.id, reembolso.id)).toThrow('Escolha dois lançamentos diferentes')

    // 3) Desvincular devolve os dois lançamentos ao resultado.
    unlinkTransfer(linked.id)
    expect(db().get('transfer', linked.id)).toBeNull()
    expect(getTransaction(out.id).transferId).toBeNull()
    expect(getTransaction(into.id).transferId).toBeNull()
    dash = buildDashboard(month)
    expect(dash.income).toBe(1599) // 1000 + 250 + 250 + 99
    expect(dash.expense).toBe(1850)
    expect(() => unlinkTransfer(linked.id)).toThrow('Transferência não encontrada')

    // 4) Excluir uma perna desfaz o vínculo e deixa a outra solta.
    deleteTransaction(tr.from.id)
    expect(getTransaction(tr.to.id).transferId).toBeNull()
  })

  it('regras da criação e do vínculo', () => {
    const { account, expense } = basicCatalog()
    const card = createAccount({ name: 'Cartão', type: 'CREDIT_CARD', initialBalance: 0, color: '#000000', closingDay: 5, dueDay: 12 })
    const savings = createAccount({ name: 'Poupança', type: 'SAVINGS', initialBalance: 0, color: '#000000' })
    const base = { amount: 10, date: '2027-04-01', description: null, paid: true }

    expect(() => createTransfer({ ...base, fromAccountId: account.id, toAccountId: account.id }))
      .toThrow('Origem e destino precisam ser contas diferentes')
    expect(() => createTransfer({ ...base, fromAccountId: account.id, toAccountId: card.id }))
      .toThrow('Cartão de crédito não entra em transferência')
    expect(() => createTransfer({ ...base, amount: 0, fromAccountId: account.id, toAccountId: savings.id })).toThrow('Dados inválidos')

    // Descrição em branco vira "Transferência"; sem `paid`, nasce paga.
    const t = createTransfer({ ...base, description: '  ', paid: undefined as unknown as boolean, fromAccountId: account.id, toAccountId: savings.id })
    expect(t.from.description).toBe('Transferência')
    expect(t.from.paid).toBe(true)
    expect(t.to.accountId).toBe(savings.id)

    // O vínculo tira a categoria das duas pernas.
    const a = tx({ description: 'Saída', amount: 30, type: 'EXPENSE', dueDate: '2027-04-02', accountId: account.id, categoryId: expense.id })
    const b = tx({ description: 'Entrada', amount: 30, type: 'INCOME', dueDate: '2027-04-02', accountId: account.id })
    const c = tx({ description: 'Entrada', amount: 31, type: 'INCOME', dueDate: '2027-04-02', accountId: savings.id })
    const d = tx({ description: 'Saída 2', amount: 30, type: 'EXPENSE', dueDate: '2027-04-02', accountId: savings.id })
    const purchase = tx({ description: 'Compra', amount: 30, type: 'EXPENSE', dueDate: '2027-04-02', accountId: card.id })
    expect(() => linkTransfer(a.id, d.id)).toThrow('Uma transferência precisa de uma saída (despesa) e uma entrada (receita)')
    expect(() => linkTransfer(a.id, b.id)).toThrow('A saída e a entrada precisam estar em contas diferentes')
    expect(() => linkTransfer(a.id, c.id)).toThrow('Os dois lançamentos precisam ter o mesmo valor')
    expect(() => linkTransfer(purchase.id, b.id)).toThrow('Compras de cartão não entram em transferência')
    const linked = linkTransfer(b.id, d.id)
    expect(linked.from.id).toBe(d.id)
    const withCategory = tx({ description: 'Saída 3', amount: 31, type: 'EXPENSE', dueDate: '2027-04-02', accountId: account.id, categoryId: expense.id })
    const l2 = linkTransfer(withCategory.id, c.id)
    expect(l2.from.categoryId).toBeNull()
    expect(db().get('transaction', withCategory.id)!.categoryId).toBeNull()
  })

  it('recusar um par de recorrentes silencia todos os meses', () => {
    const { account: main } = basicCatalog()
    const invest = createAccount({ name: 'Corretora', type: 'INVESTMENT', initialBalance: 0, color: '#123456' })
    // Saída recorrente de 500 na conta principal e entrada recorrente de 500 na corretora, ambas dia 1, 4 parcelas.
    createRecurring({ description: 'Bitcoin', amount: 500, type: 'EXPENSE', frequency: 'MONTHLY', startDate: '2026-01-01', endDate: null, occurrences: 4, accountId: main.id, categoryId: null, notes: null })
    createRecurring({ description: 'Aporte', amount: 500, type: 'INCOME', frequency: 'MONTHLY', startDate: '2026-01-01', endDate: null, occurrences: 4, accountId: invest.id, categoryId: null, notes: null })
    // As ocorrências nascem quando o mês é consultado.
    for (const month of ['2026-01', '2026-02', '2026-03', '2026-04']) listTransactions({ month })

    const before = transferSuggestions()
    expect(before.items).toHaveLength(4)
    expect(before.rejected).toBe(0)

    const pair = before.items[0]
    expect(rejectTransfer(pair.from.id, pair.to.id)).toEqual({ recurring: true })
    // Recusar de novo não duplica.
    rejectTransfer(pair.to.id, pair.from.id)

    const after = transferSuggestions()
    expect(after.items).toHaveLength(0) // os outros 3 meses sumiram junto
    expect(after.rejected).toBe(1)

    // Um lançamento avulso de 500 na corretora ainda cruza com o "Bitcoin" (a recusa foi só entre as duas regras).
    const loose = tx({ description: 'Pix recebido', amount: 500, type: 'INCOME', dueDate: '2026-02-02', accountId: invest.id })
    const looseList = transferSuggestions()
    expect(looseList.items).toHaveLength(1)
    expect(looseList.items[0].to.description).toBe('Pix recebido')

    // Recusa de lançamento avulso: só aquele par.
    expect(rejectTransfer(looseList.items[0].from.id, loose.id)).toEqual({ recurring: false })
    expect(transferSuggestions().items).toHaveLength(0)
    expect(() => rejectTransfer(loose.id, loose.id)).toThrow('Escolha dois lançamentos diferentes')

    forgetRejections()
    // Volta a cruzar: o avulso de fevereiro e o Aporte disputam o Bitcoin de fevereiro (empate de pontos: fica a entrada mais antiga, o Aporte).
    const again = transferSuggestions()
    expect(again.rejected).toBe(0)
    expect(again.items).toHaveLength(4)
    expect(again.items.map((s) => s.from.dueDate)).toEqual(['2026-04-01', '2026-03-01', '2026-02-01', '2026-01-01'])
    expect(again.items[2].to.description).toBe('Aporte')
  })

  it('pontua descrições que citam a outra conta ou o próprio usuário', () => {
    const { account } = basicCatalog()
    const other = createAccount({ name: 'Nubank', type: 'CHECKING', initialBalance: 0, color: '#123456' })
    tx({ description: 'Pagamento', amount: 80, type: 'EXPENSE', dueDate: '2027-04-01', accountId: account.id })
    tx({ description: 'Crédito João Silva', amount: 80, type: 'INCOME', dueDate: '2027-04-01', accountId: other.id })
    tx({ description: 'Envio para Nubank', amount: 70, type: 'EXPENSE', dueDate: '2027-04-02', accountId: account.id })
    tx({ description: 'Entrada', amount: 70, type: 'INCOME', dueDate: '2027-04-04', accountId: other.id })
    // Fora da janela de 3 dias.
    tx({ description: 'Saída', amount: 40, type: 'EXPENSE', dueDate: '2027-04-01', accountId: account.id })
    tx({ description: 'Entrada', amount: 40, type: 'INCOME', dueDate: '2027-04-05', accountId: other.id })

    const list = transferSuggestions('João Silva').items
    expect(list).toHaveLength(2)
    expect(list[0].from.description).toBe('Envio para Nubank')
    expect(list[0].score).toBe(100 - 2 * 15 + 20 + 15 + 5)
    expect(list[1].score).toBe(100 + 10 + 5)
    expect(transferSuggestions().items[1].score).toBe(105)
  })
})
