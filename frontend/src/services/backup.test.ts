import { beforeEach, describe, expect, it } from 'vitest'
import { strFromU8, strToU8, unzipSync, zipSync } from 'fflate'
import type { LocalStore } from '@/data/store'
import { newId, occurrenceId } from '@/domain/ids'
import { exportBackup, importBackup } from './backup'
import { createAccount, createCategory, createRule, listCategories, seedDefaults } from './catalog'
import { BusinessError, db, useStoreForTests } from './context'
import { createRecurring, ensureOccurrences } from './recurring'
import { freshStore } from './testing'
import { createTransaction, deleteTransaction, emptyTransaction } from './transactions'

/**
 * Exportar os dados da conta (zip com JSON sem ids + fotos) e importar somando: nada duplica ao importar de novo,
 * e noutro aparelho/conta tudo é recriado casando contas e categorias pelo nome (porte de BackupTest).
 */

/** "Servidor" de fotos em memória. */
function fakeServer(prefix: string) {
  const photos = new Map<string, Uint8Array>()
  return {
    photos,
    io: {
      downloadReceipt: async (file: string) => photos.get(file) ?? null,
      uploadReceipt: async (bytes: Uint8Array) => {
        const name = `${prefix}-${newId()}.jpg`
        photos.set(name, bytes)
        return name
      },
    },
  }
}

const accountByName = (name: string) => db().list('account').find((a) => a.name === name)!
const txs = () => db().list('transaction')
const find = (description: string, type?: string) =>
  txs().find((t) => t.description.startsWith(description) && (type == null || t.type === type))!

function readZip(zip: Uint8Array) {
  const files = unzipSync(zip)
  return { names: Object.keys(files), data: JSON.parse(strFromU8(files['contaslt.json'])), files }
}

/** Transferência entre contas montada direto nos registros (as duas pernas + o vínculo). */
function transfer(fromId: string, toId: string, cents: number, date: string) {
  const id = newId()
  const out = { ...emptyTransaction(), accountId: fromId, description: 'Transferência', amount: cents, type: 'EXPENSE' as const, dueDate: date, paid: true, transferId: id }
  const inn = { ...emptyTransaction(), accountId: toId, description: 'Transferência', amount: cents, type: 'INCOME' as const, dueDate: date, paid: true, transferId: id }
  db().put('transaction', out)
  db().put('transaction', inn)
  db().put('transfer', { id, fromTransactionId: out.id, toTransactionId: inn.id, createdAt: out.createdAt })
}

describe('backup', () => {
  let origin: LocalStore
  beforeEach(async () => {
    origin = await freshStore()
    seedDefaults()
  })

  it('exporta sem ids e importa somando, sem duplicar', async () => {
    const server = fakeServer('u1')
    const parent = listCategories().find((c) => c.type === 'EXPENSE')!
    const sub = createCategory({ name: 'Padaria', type: 'EXPENSE', color: '#112233', icon: 'croissant', parentId: parent.id })
    createRule('ifood', sub.id)
    const main = accountByName('Conta principal')
    const savings = createAccount({ name: 'Poupança', type: 'SAVINGS', initialBalance: 100, color: '#123456' })
    const receipt = `u1-${newId()}.jpg`
    server.photos.set(receipt, new Uint8Array([0xff, 0xd8, 1, 2, 3]))
    createTransaction({
      description: 'Mercado', amount: 31.97, type: 'EXPENSE', dueDate: '2026-10-25', paid: true, notes: null,
      accountId: main.id, categoryId: sub.id, merchant: 'ALMEIDA', receiptFile: receipt,
      items: [{ description: 'LEITE', quantity: 2, unitPrice: 4.79, total: 9.58 }, { description: 'PAO', quantity: null, unitPrice: null, total: 22.39 }],
    })
    transfer(main.id, savings.id, 5000, '2026-10-26')

    const zip = await exportBackup(server.io)
    const { names, data } = readZip(zip)
    expect(names[0]).toBe('contaslt.json')
    expect(names).toContain(`receipts/${receipt}`)
    expect(data.format).toBe('contaslt-data')
    expect(data.version).toBe(1)
    const mercado = data.transactions.find((t: { description: string }) => t.description === 'Mercado')
    expect(mercado.id).toBeUndefined()
    expect(mercado.account).toBe('Conta principal')
    expect(mercado.category).toBe(`${parent.name} › Padaria`)
    expect(mercado.amount).toBe(31.97)                      // reais no arquivo, como o servidor
    expect(mercado.items).toHaveLength(2)
    expect(mercado.items[0]).toEqual({ description: 'LEITE', quantity: 2, unitPrice: 4.79, total: 9.58 })
    const out = data.transactions.find((t: { description: string; type: string }) => t.description === 'Transferência' && t.type === 'EXPENSE')
    expect(out.transferTo).toBeGreaterThanOrEqual(0)
    expect(data.transactions[out.transferTo].type).toBe('INCOME')
    expect(data.categoryRules[0]).toEqual({ keyword: 'ifood', category: `${parent.name} › Padaria` })
    expect(data.accounts.find((a: { name: string }) => a.name === 'Poupança').initialBalance).toBe(100)

    // Importar no mesmo aparelho: tudo já existe, nada entra.
    const again = await importBackup(zip, server.io)
    expect(again).toMatchObject({ transactions: 0, skippedTransactions: 3, accounts: 0, categories: 0, categoryRules: 0, receipts: 0 })
    expect(txs()).toHaveLength(3)
    expect(db().list('account')).toHaveLength(2)

    // Apagou um lançamento por engano? Importar de novo repõe só ele (com a foto, enviada de novo).
    deleteTransaction(find('Mercado').id)
    const repaired = await importBackup(zip, server.io)
    expect(repaired).toMatchObject({ transactions: 1, skippedTransactions: 2, receipts: 1 })
    const back = find('Mercado')
    expect(back.items).toHaveLength(2)
    expect(back.items[0]).toEqual({ description: 'LEITE', quantity: 2, unitPrice: 479, total: 958 })
    expect(back.amount).toBe(3197)
    expect(back.receiptFile).toMatch(/^u1-/)
    expect(back.receiptFile).not.toBe(receipt)
    expect(server.photos.get(back.receiptFile!)).toEqual(new Uint8Array([0xff, 0xd8, 1, 2, 3]))

    // Noutra conta: contas e categorias casadas/criadas pelo nome, transferência pareada, foto no servidor novo.
    await freshStore()
    seedDefaults()
    const other = fakeServer('u2')
    const moved = await importBackup(zip, other.io)
    expect(moved).toMatchObject({ transactions: 3, accounts: 1, categories: 1, categoryRules: 1, transfers: 1, receipts: 1, skippedTransactions: 0 })
    const movedMercado = find('Mercado')
    expect(movedMercado.receiptFile).toMatch(/^u2-/)
    expect(other.photos.has(movedMercado.receiptFile!)).toBe(true)
    const padaria = db().get('category', movedMercado.categoryId)!
    expect(padaria.name).toBe('Padaria')
    expect(db().get('category', padaria.parentId)!.name).toBe(parent.name)
    expect(db().list('categoryRule')[0]).toMatchObject({ keyword: 'ifood', categoryId: padaria.id })
    const movedOut = find('Transferência', 'EXPENSE')
    const tr = db().get('transfer', movedOut.transferId)!
    expect(tr.fromTransactionId).toBe(movedOut.id)
    const peer = db().get('transaction', tr.toTransactionId)!
    expect(peer.transferId).toBe(tr.id)
    expect(db().get('account', peer.accountId)!.name).toBe('Poupança')
    expect(accountByName('Poupança').initialBalance).toBe(10000)
    expect((await importBackup(zip, other.io)).transactions).toBe(0)   // de novo: nada duplica

    useStoreForTests(origin)
    expect(txs()).toHaveLength(3)                                      // e a conta de origem não mudou
  })

  it('recorrente e seus vencimentos voltam ligados, sem o gerador duplicar', async () => {
    const main = accountByName('Conta principal')
    const bill = createRecurring({
      description: 'Aluguel', amount: 1500, type: 'EXPENSE', frequency: 'MONTHLY', startDate: '2026-01-10', endDate: null,
      occurrences: null, accountId: main.id, categoryId: null, notes: null,
    })
    ensureOccurrences('2026-03')
    const paid = txs().find((t) => t.scheduledDate === '2026-03-10')!
    db().put('transaction', { ...paid, paid: true, amount: 155000 })
    const server = fakeServer('u1')
    const zip = await exportBackup(server.io)
    const { data } = readZip(zip)
    expect(data.recurringBills).toHaveLength(1)
    expect(data.recurringBills[0]).toMatchObject({ description: 'Aluguel', amount: 1500, startDate: '2026-01-10', account: 'Conta principal' })
    const occ = data.transactions.find((t: { scheduledDate?: string }) => t.scheduledDate === '2026-03-10')
    expect(occ.recurringBill).toBe(0)

    // Mesmo aparelho: a regra é casada e a ocorrência é a mesma.
    expect(await importBackup(zip, server.io)).toMatchObject({ recurringBills: 0, transactions: 0 })
    expect(db().list('recurringBill')).toHaveLength(1)
    expect(db().list('recurringBill')[0].id).toBe(bill.id)

    await freshStore()
    seedDefaults()
    const restored = await importBackup(zip, fakeServer('u2').io)
    expect(restored.recurringBills).toBe(1)
    const newBill = db().list('recurringBill')[0]
    const march = txs().find((t) => t.scheduledDate === '2026-03-10')!
    expect(march.recurringBillId).toBe(newBill.id)
    expect(march.id).toBe(occurrenceId(newBill.id, '2026-03-10'))
    expect(march.paid).toBe(true)
    expect(march.amount).toBe(155000)
    const before = txs().length
    ensureOccurrences('2026-03')
    expect(txs()).toHaveLength(before)
  })

  it('lê o arquivo exportado pelo servidor antigo', async () => {
    const json = {
      format: 'contaslt-data', version: 1, exportedAt: '2026-10-30T10:00:00', app: 'ContasLT',
      accounts: [
        { name: 'conta principal ', type: 'CHECKING', initialBalance: 0.00, color: '#1B5E3F', archived: false, closingDay: null, dueDay: null },
        { name: 'Nubank', type: 'CREDIT_CARD', initialBalance: 0, color: '#8A05BE', archived: false, closingDay: 3, dueDay: 10 },
      ],
      categories: [{ name: 'Lanches', type: 'EXPENSE', color: '#112233', icon: 'tag', archived: false, parent: 'Alimentação' }],
      categoryRules: [{ keyword: 'padaria', category: 'Alimentação › Lanches' }, { keyword: 'x', category: 'Não existe' }],
      recurringBills: [],
      transactions: [
        { description: 'Fatura', amount: '1234.50', type: 'EXPENSE', date: '2026-10-10', paid: true, account: 'Conta principal',
          category: null, notes: null, externalId: 'FIT1', merchant: null, receipt: 'u12-0b6c3c1e-1111-4222-8333-444455556666.jpg',
          receiptAccessKey: null, cardAccount: 'Nubank', invoiceMonth: '2026-10', items: [] },
        { description: 'Pão', amount: 12.3, type: 'EXPENSE', date: '2026-10-11', paid: true, account: 'Nubank',
          category: 'Alimentação › Lanches', notes: null, externalId: 'FIT1', merchant: null, receipt: null,
          receiptAccessKey: null, items: [{ description: 'PAO', quantity: 1.5, unitPrice: '8.20', total: '12.30' }] },
      ],
    }
    const zip = zipSync({
      'contaslt.json': strToU8(JSON.stringify(json)),
      'receipts/u12-0b6c3c1e-1111-4222-8333-444455556666.jpg': new Uint8Array([9, 9]),
      'receipts/sobra.jpg': new Uint8Array([1]),
    })
    const server = fakeServer('u3')
    const r = await importBackup(zip, server.io)
    expect(r).toMatchObject({ accounts: 1, categories: 1, categoryRules: 1, transactions: 2, receipts: 1 })
    expect(server.photos.size).toBe(1)
    const fatura = find('Fatura')
    expect(fatura.amount).toBe(123450)
    expect(fatura.accountId).toBe(accountByName('Conta principal').id)   // casada pelo nome, sem caixa/espaços
    expect(fatura.cardAccountId).toBe(accountByName('Nubank').id)
    expect(fatura.cardInvoiceMonth).toBe('2026-10-01')
    expect(fatura.receiptFile).toMatch(/^u3-/)
    const pao = find('Pão')
    expect(pao.items[0]).toEqual({ description: 'PAO', quantity: 1.5, unitPrice: 820, total: 1230 })
    expect(db().get('category', pao.categoryId)!.name).toBe('Lanches')
    expect(accountByName('Nubank')).toMatchObject({ closingDay: 3, dueDay: 10 })
  })

  it('recusa arquivos que não são exportações', async () => {
    const io = fakeServer('u1').io
    await expect(importBackup(strToU8('{"format":"contaslt-data"}'), io)).rejects.toBeInstanceOf(BusinessError)
    await expect(importBackup(zipSync({ 'outro.json': strToU8('{}') }), io)).rejects.toBeInstanceOf(BusinessError)
    await expect(importBackup(zipSync({ 'contaslt.json': strToU8('{"format":"outro"}') }), io)).rejects.toThrow('não é uma exportação')
    await expect(importBackup(zipSync({ 'contaslt.json': strToU8('{"format":"contaslt-data","version":2}') }), io)).rejects.toThrow('versão mais nova')
  })
})
