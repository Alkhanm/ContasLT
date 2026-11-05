import { readFileSync } from 'node:fs'
import { beforeEach, describe, expect, it } from 'vitest'
import type { ConfirmRow, Id, ImportPreview, PreviewRow } from '@/api/types'
import { ApiError } from '@/api/client'
import { createAccount, listAccounts, listRules } from './catalog'
import { buildDashboard } from './dashboard'
import { confirmImport, deleteImport, detectSource, importHistory, previewImport } from './imports'
import { createRecurring } from './recurring'
import { basicCatalog, freshStore } from './testing'
import { createTransaction, getTransaction, listTransactions } from './transactions'
import { summarizeInvoice, requireCard } from './cards'

/** Cenários de importação de ApiFlowTest, RecurringMatchTest e CardInvoiceTest. */

const sample = () => new Uint8Array(readFileSync(new URL('../../../backend/src/test/resources/samples/extrato.ofx', import.meta.url)))

function row(date: string, amount: string, memo: string, fitid: string) {
  return `<STMTTRN>\n<TRNTYPE>${amount.startsWith('-') ? 'DEBIT' : 'CREDIT'}\n<DTPOSTED>${date.replaceAll('-', '')}`
    + `\n<TRNAMT>${amount}\n<FITID>${fitid}\n<MEMO>${memo}\n</STMTTRN>\n`
}

function ofx(...rows: string[]): Uint8Array {
  const text = 'OFXHEADER:100\nDATA:OFXSGML\nVERSION:102\nSECURITY:NONE\nENCODING:USASCII\n'
    + 'CHARSET:1252\nCOMPRESSION:NONE\nOLDFILEUID:NONE\nNEWFILEUID:NONE\n\n<OFX>\n<BANKMSGSRSV1>\n<STMTTRNRS>\n<STMTRS>\n'
    + '<CURDEF>BRL\n<BANKTRANLIST>\n' + rows.join('') + '</BANKTRANLIST>\n</STMTRS>\n</STMTTRNRS>\n</BANKMSGSRSV1>\n</OFX>\n'
  return Uint8Array.from([...text].map((c) => c.charCodeAt(0) & 0xff))
}

const preview = (accountId: Id, bytes: Uint8Array, name = 'extrato.ofx') => previewImport(accountId, { name, bytes })

function rowNamed(pv: ImportPreview, fragment: string): PreviewRow {
  const r = pv.rows.find((x) => x.description.includes(fragment))
  if (!r) throw new Error(`linha não encontrada: ${fragment}`)
  return r
}

function confirmRow(r: PreviewRow, extra: Partial<ConfirmRow> = {}): ConfirmRow {
  return {
    date: r.date, description: r.description, amount: r.amount, type: r.type, externalId: r.externalId,
    categoryId: null, paid: true, learnRule: false, ...extra,
  }
}

function status(fn: () => unknown): number {
  try {
    fn()
  } catch (e) {
    if (e instanceof ApiError) return e.status
    throw e
  }
  return 0
}

describe('importação de extrato (ApiFlowTest)', () => {
  beforeEach(async () => { await freshStore() })

  it('prévia e confirmação do OFX pulam duplicados e aprendem regras; excluir a importação remove os lançamentos', () => {
    const { account, expense, income } = basicCatalog()
    const pv = preview(account.id, sample())
    expect(pv.source).toBe('OFX')
    expect(pv.rows).toHaveLength(3)
    expect(pv.duplicates).toBe(0)
    expect(pv.rows.map((r) => r.date)).toEqual(['2026-10-07', '2026-10-05', '2026-10-03'])

    const rows = pv.rows.map((r) => confirmRow(r, { categoryId: r.type === 'INCOME' ? income.id : expense.id, learnRule: true }))
    const res = confirmImport({ accountId: account.id, source: 'OFX', filename: 'extrato.ofx', rows })
    expect(res.imported).toBe(3)

    // Segunda prévia do mesmo arquivo marca tudo como duplicado e sugere categorias aprendidas.
    const again = preview(account.id, sample())
    expect(again.duplicates).toBe(3)
    expect(again.rows[0].suggestedCategoryId).toBe(expense.id)

    const reconfirm = confirmImport({ accountId: account.id, source: 'OFX', filename: '', rows })
    expect(reconfirm.skipped).toBe(3)
    expect(listRules()).toHaveLength(3)

    expect(buildDashboard('2026-10').income).toBe(4500)

    // Histórico: o lote vazio da reconfirmação e o do primeiro envio.
    const history = importHistory()
    expect(history).toHaveLength(2)
    const first = history.find((b) => b.id === res.batchId)!
    expect(first).toMatchObject({ source: 'OFX', filename: 'extrato.ofx', accountName: 'Conta', importedCount: 3 })

    const before = listTransactions({ month: '2026-10' }).items.length
    expect(deleteImport(res.batchId)).toEqual({ removed: 3 })
    expect(listTransactions({ month: '2026-10' }).items.length).toBe(before - 3)
    expect(status(() => deleteImport(res.batchId))).toBe(404)
  })

  it('lançamento de comprovante com itens é vinculado pela importação do OFX', () => {
    const { account, expense, income } = basicCatalog()
    const tx = createTransaction({
      description: 'Padaria Pão Quente', merchant: 'Padaria Pão Quente', amount: 52.3, type: 'EXPENSE',
      dueDate: '2026-10-06', paid: true, notes: null, accountId: account.id, categoryId: expense.id,
      items: [
        { description: 'Pão francês', quantity: 10, unitPrice: 0.8, total: 8 },
        { description: 'Bolo de cenoura', quantity: 1, unitPrice: 44.3, total: 44.3 },
      ],
    }).transaction

    // A prévia reconhece a linha de 52,30 (07/10) como a mesma compra.
    const pv = preview(account.id, sample())
    const matched = pv.rows.find((r) => r.matchTransactionId != null)!
    expect(matched).toBeDefined()
    expect(matched.amount).toBe(52.3)
    expect(matched.matchTransactionId).toBe(tx.id)
    expect(matched.matchItemCount).toBe(2)
    expect(matched.matchCategoryId).toBe(expense.id)
    expect(matched.matchAccountName).toBe('Conta')
    expect(matched.duplicate).toBe(false)

    const rows = pv.rows.map((r) => r.matchTransactionId != null
      ? confirmRow(r, { linkTransactionId: r.matchTransactionId })
      : confirmRow(r, { categoryId: r.type === 'INCOME' ? income.id : expense.id }))
    const res = confirmImport({ accountId: account.id, source: 'OFX', filename: 'extrato.ofx', rows })
    expect(res.linked).toBe(1)
    expect(res.imported).toBe(2)

    const after = getTransaction(tx.id)
    expect(after.externalId).toBe(matched.externalId)
    expect(after.importBatchId).toBe(res.batchId)
    expect(after.items).toHaveLength(2)
    expect(after.description).toBe('Padaria Pão Quente')
    expect(importHistory()[0].importedCount).toBe(3)

    // Importar o mesmo extrato de novo: a linha agora é duplicada (FITID já usado), não sugere vínculo.
    const again = preview(account.id, sample())
    expect(again.duplicates).toBe(3)
    expect(again.rows.every((r) => r.matchTransactionId == null)).toBe(true)

    // Vincular de novo é recusado.
    expect(() => confirmImport({ accountId: account.id, source: 'OFX', filename: 'x.ofx', rows: [confirmRow(matched, { externalId: 'outro', linkTransactionId: tx.id })] }))
      .toThrow(`O lançamento "Padaria Pão Quente" já está vinculado a uma importação`)

    // Excluir a importação apaga só os 2 criados por ela; o lançamento vinculado fica, sem o vínculo.
    expect(deleteImport(res.batchId).removed).toBe(2)
    const kept = getTransaction(tx.id)
    expect(kept.importBatchId).toBeNull()
    expect(kept.externalId).toBeNull()
    expect(kept.items).toHaveLength(2)
  })

  it('confirmação é tudo ou nada e valida a requisição', () => {
    const { account, income } = basicCatalog()
    const pv = preview(account.id, sample())
    // A linha de despesa com categoria de receita derruba a confirmação inteira: nada é gravado.
    const rows = pv.rows.map((r) => confirmRow(r, { categoryId: income.id }))
    expect(() => confirmImport({ accountId: account.id, source: 'OFX', filename: 'extrato.ofx', rows }))
      .toThrow('Categoria "Salário" não é do tipo do lançamento "IFOOD *RESTAURANTE"')
    expect(importHistory()).toHaveLength(0)
    expect(listTransactions({ month: '2026-10' }).items).toHaveLength(0)

    try {
      confirmImport({ accountId: account.id, source: 'OFX', filename: 'x', rows: [] })
      expect.unreachable()
    } catch (e) {
      expect((e as ApiError).status).toBe(400)
      expect((e as ApiError).errors.rows).toBe('Selecione ao menos um lançamento')
    }
    expect(status(() => confirmImport({ accountId: account.id, source: 'OFX', filename: 'x',
      rows: [confirmRow(pv.rows[0], { linkTransactionId: 'nao-existe' })] }))).toBe(404)
  })

  it('formato do arquivo pela extensão ou pelo conteúdo', () => {
    expect(detectSource('a.OFX', new Uint8Array())).toBe('OFX')
    expect(detectSource('a.xls', new Uint8Array())).toBe('EXCEL')
    expect(detectSource('arquivo', sample())).toBe('OFX')
    expect(detectSource('arquivo', Uint8Array.from([0x50, 0x4b, 3, 4, 0]))).toBe('EXCEL')
    expect(() => detectSource('a.csv', new TextEncoder().encode('x;y'))).toThrow('Formato não suportado. Envie um arquivo .ofx, .xlsx ou .xls')
    const { account } = basicCatalog()
    expect(() => preview(account.id, new TextEncoder().encode('oi'), 'a.ofx')).toThrow('O arquivo não parece ser um extrato OFX válido')
    expect(status(() => preview(account.id, new TextEncoder().encode('oi'), 'a.ofx'))).toBe(422)
  })
})

describe('extrato casa com vencimentos de recorrente (RecurringMatchTest)', () => {
  beforeEach(async () => { await freshStore() })

  const bill = (accountId: Id, description: string, amount: number, startDate: string, matchStrictness?: number, fromTransactionId?: Id) =>
    createRecurring({
      description, amount, type: 'EXPENSE', frequency: 'MONTHLY', startDate, endDate: null, occurrences: null,
      accountId, categoryId: null, notes: null, matchStrictness, fromTransactionId,
    }).id

  it('linha do extrato quita o vencimento pendente com valor e data reais', () => {
    const { account } = basicCatalog()
    const billId = bill(account.id, 'Internet LINQ', 99.99, '2035-03-01')

    // Boleto pago no dia 10 com multa: valor e data diferentes, mas o nome bate. O mercado não bate com nada.
    const pv = preview(account.id, ofx(
      row('2035-03-10', '-102.00', 'Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA', 'A1'),
      row('2035-03-10', '-102.00', 'SUPERMERCADO ALMEIDA', 'A2')))
    const linq = rowNamed(pv, 'LINQ')
    const market = rowNamed(pv, 'SUPERMERCADO')
    expect(linq.matchTransactionId).not.toBeNull()
    expect(linq.matchRecurring).toBe(true)
    expect(linq.matchDescription).toBe('Internet LINQ')
    expect(linq.matchDate).toBe('2035-03-01')
    expect(linq.matchAmount).toBe(99.99)
    expect(market.matchTransactionId).toBeNull()
    const occurrenceId = linq.matchTransactionId!

    const result = confirmImport({ accountId: account.id, source: 'OFX', filename: 'extrato.ofx', rows: [
      confirmRow(linq, { linkTransactionId: occurrenceId }), confirmRow(market)] })
    expect(result.linked).toBe(1)
    expect(result.imported).toBe(1)

    // O vencimento virou o pagamento real: pago, com valor e data do extrato, e a data prevista guardada.
    const occ = getTransaction(occurrenceId)
    expect(occ.paid).toBe(true)
    expect(occ.amount).toBe(102)
    expect(occ.dueDate).toBe('2035-03-10')
    expect(occ.scheduledDate).toBe('2035-03-01')
    expect(occ.expectedAmount).toBe(99.99)
    expect(occ.recurringBillId).toBe(billId)
    expect(occ.description).toBe('Internet LINQ')

    // Março tem só o vencimento pago e o mercado: nada duplicado, e o gerador não recria o dia 1º.
    expect(listTransactions({ month: '2035-03' }).items).toHaveLength(2)
    // Abril segue previsto com o valor estimado da regra.
    const april = listTransactions({ month: '2035-04' }).items
    expect(april).toHaveLength(1)
    expect(april[0].amount).toBe(99.99)
  })

  it('a precisão controla quanto data e valor podem variar', () => {
    const { account } = basicCatalog()
    bill(account.id, 'Energia Enel', 200, '2035-05-05', 10)
    bill(account.id, 'Água Saneago', 80, '2035-05-05', 1)

    const pv = preview(account.id, ofx(
      row('2035-05-12', '-204.00', 'PAGTO ENEL DISTRIBUICAO', 'B1'),
      row('2035-05-28', '-115.00', 'SANEAGO SANEAMENTO DE GOIAS', 'B2')))
    // Nível 10: só casaria com valor e data exatos.
    expect(rowNamed(pv, 'ENEL').matchTransactionId).toBeNull()
    // Nível 1: praticamente só o nome, dentro do mês.
    expect(rowNamed(pv, 'SANEAGO').matchTransactionId).not.toBeNull()
    expect(rowNamed(pv, 'SANEAGO').matchDescription).toBe('Água Saneago')

    const exact = preview(account.id, ofx(row('2035-05-05', '-200.00', 'PAGTO ENEL DISTRIBUICAO', 'B3')))
    expect(exact.rows[0].matchDescription).toBe('Energia Enel')
  })

  it('pagamentos anteriores ensinam como a conta aparece no extrato', () => {
    const { account } = basicCatalog()
    // Regra com nome "Internet" não compartilha palavra com o extrato; o pagamento anterior adotado sim.
    const tx = createTransaction({
      description: 'Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA', amount: 99.99, type: 'EXPENSE',
      dueDate: '2035-07-20', paid: true, notes: null, accountId: account.id, categoryId: null,
    }).transaction
    bill(account.id, 'Internet', 99.99, '2035-07-20', undefined, tx.id)

    const pv = preview(account.id, ofx(row('2035-08-22', '-99.99', 'Pagamento de boleto efetuado - LINQ TELECOMUNICACOES LTDA', 'C1')))
    expect(pv.rows[0].matchDescription).toBe('Internet')
    expect(pv.rows[0].matchDate).toBe('2035-08-20')
  })
})

describe('pagamento de fatura no extrato (CardInvoiceTest)', () => {
  beforeEach(async () => { await freshStore() })

  it('linha do extrato que paga a fatura vira despesa ligada ao cartão', () => {
    const { account: checking } = basicCatalog()
    // Fecha dia 25, vence dia 5 do mês seguinte.
    const card = createAccount({ name: 'Nubank', type: 'CREDIT_CARD', initialBalance: 0, color: '#820AD1', closingDay: 25, dueDay: 5 })
    createTransaction({ description: 'Mercado', amount: 300, type: 'EXPENSE', dueDate: '2034-09-10', paid: true, notes: null, accountId: card.id, categoryId: null })

    const pv = preview(checking.id, ofx(row('2034-10-05', '-480.00', 'PAGAMENTO FATURA NUBANK', 'F1'), row('2034-10-06', '-35.90', 'PADARIA', 'F2')))
    const line = rowNamed(pv, 'FATURA')
    expect(line.invoiceCardId).toBe(card.id)
    expect(line.invoiceCardName).toBe('Nubank')
    expect(line.invoiceMonth).toBe('2034-10')
    expect(line.invoiceDueDate).toBe('2034-10-05')
    expect(line.invoiceTotal).toBe(300)
    expect(line.invoicePaid).toBe(0)
    expect(rowNamed(pv, 'PADARIA').invoiceCardId).toBeNull()

    const res = confirmImport({ accountId: checking.id, source: 'OFX', filename: 'extrato.ofx', rows: [
      { date: '2034-10-05', description: 'PAGAMENTO FATURA NUBANK', amount: 480, type: 'EXPENSE', externalId: 'F1', categoryId: null, paid: true, learnRule: false, invoiceCardId: card.id, invoiceMonth: '2034-10' },
      { date: '2034-10-06', description: 'PADARIA', amount: 35.9, type: 'EXPENSE', externalId: 'F2', categoryId: null, paid: true, learnRule: false },
    ] })
    expect(res.invoicePayments).toBe(1)

    // A fatura é despesa da conta corrente (480 + 35,90) e agrega a compra do cartão.
    expect(listTransactions({ month: '2034-10', accountId: checking.id }).summary.expense).toBe(515.9)
    const inv = summarizeInvoice(requireCard(card.id), '2034-10')
    expect(inv.paid).toBe(480)
    expect(inv.undetailed).toBe(180)
    expect(inv.status).toBe('PAID')
    expect(listAccounts().find((a) => a.id === card.id)!.balance).toBe(0)

    // Pagamento de fatura a partir do próprio cartão é recusado.
    expect(() => confirmImport({ accountId: card.id, source: 'OFX', filename: 'x', rows: [
      { date: '2034-10-05', description: 'PAGAMENTO', amount: 1, type: 'EXPENSE', externalId: null, categoryId: null, paid: true, learnRule: false, invoiceCardId: card.id },
    ] })).toThrow('Pagamento de fatura é uma saída da conta: "PAGAMENTO"')

    // OFX do próprio cartão: "Pagamento recebido" é sinalizado para não virar receita nem estorno.
    const cardPv = preview(card.id, ofx(row('2034-10-05', '480.00', 'Pagamento recebido', 'C1'), row('2034-09-12', '-45.00', 'IFOOD', 'C2')), 'cartao.ofx')
    expect(rowNamed(cardPv, 'Pagamento recebido').invoicePaymentReceived).toBe(true)
    expect(rowNamed(cardPv, 'IFOOD').invoicePaymentReceived).toBe(false)
  })
})
