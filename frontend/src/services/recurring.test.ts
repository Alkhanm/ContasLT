import { beforeEach, describe, expect, it } from 'vitest'
import type { Candidate } from '@/domain/matcher'
import { createRecurring, listRecurring, matchPending, settle } from './recurring'
import { db } from './context'
import { freshStore } from './testing'
import { createAccount } from './catalog'
import { createTransaction, getTransaction, listTransactions, requireTransaction, updateTransaction } from './transactions'

/**
 * Porte de RecurringEditScopeTest, RecurringFromTransactionTest e das partes de RecurringMatchTest que não dependem
 * do importador (a prévia do extrato vira uma chamada direta a matchPending/settle).
 */

let accountId: string

beforeEach(async () => {
  await freshStore()
  accountId = createAccount({ name: 'Conta principal', type: 'CHECKING', initialBalance: 0, color: '#1B5E3F' }).id
})

function bill(description: string, amount: number, startDate: string, occurrences: number | null = null, matchStrictness?: number) {
  return createRecurring({
    description, amount, type: 'EXPENSE', frequency: 'MONTHLY', startDate, endDate: null, occurrences, accountId,
    categoryId: null, notes: null, matchStrictness,
  }).id
}

function edit(id: string, description: string, amount: number, dueDate: string, scope: 'THIS' | 'FOLLOWING') {
  return updateTransaction(id, { description, amount, type: 'EXPENSE', dueDate, paid: false, notes: null, accountId, categoryId: null }, scope)
}

const items = (month: string) => listTransactions({ month }).items

function transaction(description: string, amount: number, dueDate: string) {
  return createTransaction({ description, amount, type: 'EXPENSE', dueDate, paid: true, notes: null, accountId, categoryId: null }).transaction.id
}

describe('escopo de edição de uma ocorrência', () => {
  it('mover uma ocorrência não recria a data original', () => {
    bill('Internet', 100, '2033-03-20')
    let april = items('2033-04')
    expect(april).toHaveLength(1)
    const id = april[0].id

    edit(id, 'Internet', 100, '2033-04-01', 'THIS')
    april = items('2033-04')
    expect(april).toHaveLength(1)
    expect(april[0].dueDate).toBe('2033-04-01')
    edit(id, 'Internet', 100, '2033-04-02', 'THIS')
    expect(items('2033-04')).toHaveLength(1)

    expect(items('2033-05')[0].dueDate).toBe('2033-05-20')
  })

  it('valor novo vale para esta e as próximas, só', () => {
    const billId = bill('Academia', 100, '2033-03-20')
    items('2033-03')
    items('2033-04')
    const mayId = items('2033-05')[0].id

    edit(mayId, 'Academia Plus', 150, '2033-05-20', 'FOLLOWING')

    expect(items('2033-04')[0].amount).toBe(100)
    expect(items('2033-05')[0].amount).toBe(150)
    const june = items('2033-06')[0]
    expect(june.amount).toBe(150)
    expect(june.description).toBe('Academia Plus')
    expect(june.dueDate).toBe('2033-06-20')

    const b = listRecurring().find((r) => r.id === billId)!
    expect(b.amount).toBe(150)
    expect(b.description).toBe('Academia Plus')
  })

  it('data nova reancora os próximos vencimentos', () => {
    bill('Aluguel', 1500, '2033-03-20')
    items('2033-05')
    const juneId = items('2033-06')[0].id
    items('2033-07')

    edit(juneId, 'Aluguel', 1500, '2033-06-01', 'FOLLOWING')

    expect(items('2033-05')[0].dueDate).toBe('2033-05-20')
    const june = items('2033-06')
    expect(june).toHaveLength(1)
    expect(june[0].id).toBe(juneId)
    expect(june[0].dueDate).toBe('2033-06-01')
    const july = items('2033-07')
    expect(july).toHaveLength(1)
    expect(july[0].dueDate).toBe('2033-07-01')
    expect(items('2033-08')[0].dueDate).toBe('2033-08-01')
  })

  it('reancorar parcelamento mantém numeração e total', () => {
    bill('Geladeira', 300, '2034-01-10', 6)
    items('2034-01')
    items('2034-02')
    const third = items('2034-03')[0]
    expect(third.installmentNumber).toBe(3)

    edit(third.id, 'Geladeira', 300, '2034-03-01', 'FOLLOWING')

    const april = items('2034-04')[0]
    expect(april.dueDate).toBe('2034-04-01')
    expect(april.installmentNumber).toBe(4)
    expect(april.installmentTotal).toBe(6)
    expect(items('2034-06')[0].installmentNumber).toBe(6)
    expect(items('2034-07')).toHaveLength(0)
    expect(items('2034-02')[0].dueDate).toBe('2034-02-10')
  })
})

describe('lançamento existente vira conta recorrente', () => {
  it('o lançamento importado vira a primeira ocorrência, sem duplicar', () => {
    const txId = createTransaction({
      description: 'PAGTO ENEL DISTRIBUICAO', amount: 189.9, type: 'EXPENSE', dueDate: '2031-05-10', paid: true, notes: null,
      accountId, categoryId: null,
    }).transaction.id
    const req = {
      description: 'Conta de luz Enel', amount: 189.9, type: 'EXPENSE' as const, frequency: 'MONTHLY' as const,
      startDate: '2031-05-10', endDate: null, occurrences: null, accountId, categoryId: null, notes: null, fromTransactionId: txId,
    }
    const billId = createRecurring(req).id

    const may = items('2031-05')
    expect(may).toHaveLength(1)
    expect(may[0].id).toBe(txId)
    expect(may[0].recurringBillId).toBe(billId)
    expect(may[0].description).toBe('PAGTO ENEL DISTRIBUICAO')
    expect(may[0].paid).toBe(true)

    const june = items('2031-06')
    expect(june).toHaveLength(1)
    expect(june[0].recurringBillId).toBe(billId)
    expect(june[0].dueDate).toBe('2031-06-10')
    expect(june[0].paid).toBe(false)

    expect(() => createRecurring({ ...req, description: 'Outra' })).toThrow(expect.objectContaining({ status: 422 }))
  })
})

describe('extrato e comprovante casam com vencimentos pendentes', () => {
  interface Row { date: string; amount: number; description: string }
  const toCandidate = (r: Row): Candidate => ({
    description: r.description, amount: Math.round(Math.abs(r.amount) * 100), type: r.amount < 0 ? 'EXPENSE' : 'INCOME', date: r.date,
  })
  function preview(...rows: Row[]) {
    const dates = rows.map((r) => r.date).sort()
    return matchPending(rows, toCandidate, dates[0], dates[dates.length - 1])
  }

  it('linha do extrato quita o vencimento com valor e data reais', () => {
    const billId = bill('Internet LINQ', 99.99, '2035-03-01')
    const linq = { date: '2035-03-10', amount: -102, description: 'Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA' }
    const market = { date: '2035-03-10', amount: -102, description: 'SUPERMERCADO ALMEIDA' }
    const pv = preview(linq, market)
    const m = pv.get(linq)!
    expect(m).toBeDefined()
    expect(m.occurrence.description).toBe('Internet LINQ')
    expect(m.occurrence.dueDate).toBe('2035-03-01')
    expect(m.occurrence.amount).toBe(9999)
    expect(pv.has(market)).toBe(false)

    // A confirmação do importador: o vencimento vira o pagamento; o mercado vira lançamento novo.
    db().put('transaction', { ...settle(m.occurrence, 10200, linq.date), externalId: 'A1' })
    createTransaction({ description: market.description, amount: 102, type: 'EXPENSE', dueDate: market.date, paid: true, notes: null, accountId, categoryId: null })

    const occ = getTransaction(m.occurrence.id)
    expect(occ.paid).toBe(true)
    expect(occ.amount).toBe(102)
    expect(occ.dueDate).toBe('2035-03-10')
    expect(occ.scheduledDate).toBe('2035-03-01')
    expect(occ.expectedAmount).toBe(99.99)
    expect(occ.recurringBillId).toBe(billId)
    expect(occ.description).toBe('Internet LINQ')

    expect(items('2035-03')).toHaveLength(2)
    const april = items('2035-04')
    expect(april).toHaveLength(1)
    expect(april[0].amount).toBe(99.99)
  })

  it('a precisão controla quanto data e valor podem variar', () => {
    bill('Energia Enel', 200, '2035-05-05', null, 10)
    bill('Água Saneago', 80, '2035-05-05', null, 1)
    const enel = { date: '2035-05-12', amount: -204, description: 'PAGTO ENEL DISTRIBUICAO' }
    const saneago = { date: '2035-05-28', amount: -115, description: 'SANEAGO SANEAMENTO DE GOIAS' }
    const pv = preview(enel, saneago)
    expect(pv.has(enel)).toBe(false)
    expect(pv.get(saneago)?.occurrence.description).toBe('Água Saneago')

    const exact = { date: '2035-05-05', amount: -200, description: 'PAGTO ENEL DISTRIBUICAO' }
    expect(preview(exact).get(exact)?.occurrence.description).toBe('Energia Enel')
  })

  it('pagamentos anteriores ensinam como a conta aparece no extrato', () => {
    const txId = transaction('Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA', 99.99, '2035-07-20')
    createRecurring({
      description: 'Internet', amount: 99.99, type: 'EXPENSE', frequency: 'MONTHLY', startDate: '2035-07-20', endDate: null,
      occurrences: null, accountId, categoryId: null, notes: null, fromTransactionId: txId,
    })
    const row = { date: '2035-08-22', amount: -99.99, description: 'Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA' }
    const m = preview(row).get(row)!
    expect(m.occurrence.description).toBe('Internet')
    expect(m.occurrence.dueDate).toBe('2035-08-20')
  })

  it('comprovante sugere o vencimento pendente que ele paga', () => {
    const billId = bill('Internet LINQ', 99.99, '2035-09-01')
    const scan = { date: '2035-09-09', amount: -101.5, description: 'LINQ Telecomunicacoes' }
    const m = preview(scan).get(scan)!
    expect(m.occurrence.description).toBe('Internet LINQ')
    expect(m.occurrence.dueDate).toBe('2035-09-01')
    expect(m.occurrence.amount).toBe(9999)
    expect(m.occurrence.accountId).toBe(accountId)

    // O app salva o comprovante por cima do vencimento, que fica pago com o valor real.
    updateTransaction(m.occurrence.id, {
      description: 'Internet LINQ', amount: 101.5, type: 'EXPENSE', dueDate: '2035-09-09', paid: true, notes: null, accountId,
      categoryId: null, merchant: 'LINQ Telecomunicacoes',
    })
    const sept = items('2035-09')
    expect(sept).toHaveLength(1)
    expect(sept[0].recurringBillId).toBe(billId)
    expect(sept[0].paid).toBe(true)
    expect(sept[0].scheduledDate).toBe('2035-09-01')
    expect(requireTransaction(m.occurrence.id).amount).toBe(10150)
  })
})
