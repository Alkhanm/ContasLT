import { beforeEach, describe, expect, it } from 'vitest'
import { applyScan, fixYear, isValidAccessKey } from './receipts'
import { basicCatalog, freshStore } from './testing'
import { createTransaction, requireTransaction } from './transactions'
import { saveRule } from './catalog'

/** Leitura do worker aplicada no lançamento já salvo (antes ReceiptWorkerFlowTest, no servidor). */
const SCAN = {
  merchant: 'SUPERMERCADO ALMEIDA', issueDate: '2026-10-25', dueDate: null, paid: true, total: 31.97,
  paymentMethod: 'Pix', accessKey: null,
  items: [
    { description: 'BATATA LAYS', quantity: 2, unitPrice: 10.99, total: 21.98 },
    { description: 'ENERGETICO', quantity: 1, unitPrice: 9.99, total: 9.99 },
  ],
}

describe('leitura adiada do comprovante', () => {
  beforeEach(async () => { await freshStore() })

  it('itens, estabelecimento e categoria entram; total e data diferentes viram aviso', () => {
    const { account, expense } = basicCatalog()
    saveRule('almeida', expense.id)
    const { transaction } = createTransaction({
      description: 'Mercado', amount: 50, type: 'EXPENSE', dueDate: '2026-10-20', paid: true, notes: null,
      accountId: account.id, categoryId: null, receiptFile: 'u1-x.jpg',
    })
    const out = applyScan(requireTransaction(transaction.id), SCAN, null)
    expect(out.items).toHaveLength(2)
    expect(out.items[0]).toEqual({ description: 'BATATA LAYS', quantity: 2, unitPrice: 1099, total: 2198 })
    expect(out.merchant).toBe('SUPERMERCADO ALMEIDA')
    expect(out.categoryId).toBe(expense.id)
    expect(out.amount).toBe(5000)
    expect(out.receiptScanStatus).toBe('DIVERGENT')
    expect(out.receiptScanTotal).toBe(3197)
    expect(out.receiptScanDate).toBe('2026-10-25')
  })

  it('mesmo total e data: sem aviso, e o que o usuário preencheu fica', () => {
    const { account, income } = basicCatalog()
    const { transaction } = createTransaction({
      description: 'Mercado', amount: 31.97, type: 'EXPENSE', dueDate: '2026-10-25', paid: true, notes: null,
      accountId: account.id, categoryId: null, merchant: 'Almeida', receiptFile: 'u1-x.jpg',
      items: [{ description: 'Feira', quantity: null, unitPrice: null, total: 31.97 }],
    })
    void income
    const out = applyScan({ ...requireTransaction(transaction.id), receiptScanStatus: 'PENDING' }, SCAN, null)
    expect(out.receiptScanStatus).toBeNull()
    expect(out.merchant).toBe('Almeida')
    expect(out.items).toHaveLength(1)
  })

  it('ano corrigido pela chave de acesso válida', () => {
    // UF 35, emissão 2610 (out/2026), CNPJ, modelo 65... dígito verificador calculado.
    const base = '3526101234567800019065001000000001123456789'
    let sum = 0
    let w = 2
    for (let i = 42; i >= 0; i--) { sum += Number(base[i]) * w; w = w === 9 ? 2 : w + 1 }
    const dv = 11 - (sum % 11) >= 10 ? 0 : 11 - (sum % 11)
    const key = base + dv
    expect(isValidAccessKey(key)).toBe(true)
    expect(fixYear('2025-10-25', key)).toBe('2026-10-25')
    expect(fixYear('2025-09-25', key)).toBe('2025-09-25')
    expect(fixYear('2025-10-25', '123')).toBe('2025-10-25')
  })
})
