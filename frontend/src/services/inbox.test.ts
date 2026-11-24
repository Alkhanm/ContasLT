import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BankNotificationRec } from '@/data/schema'
import { createAccount, saveRule } from './catalog'
import { db } from './context'
import { confirm, discard, listInbox, markConfirmed, notesFor, pendingCount } from './inbox'
import { basicCatalog, freshStore } from './testing'
import { createTransaction, requireTransaction } from './transactions'

/** Caixa "A confirmar" no PWA: notificações lidas no celular, confirmadas aqui (mesmas regras do Android). */
function notification(over: Partial<BankNotificationRec> = {}): BankNotificationRec {
  const rec: BankNotificationRec = {
    id: crypto.randomUUID(), packageName: 'com.nu.production', appLabel: 'Nubank', title: 'Pix enviado',
    text: 'Você enviou R$ 50,00 para Padaria Pão Quente', postedAt: new Date(2026, 10, 5, 14, 32).getTime(),
    hash: 'h', status: 'PENDING', type: 'EXPENSE', amount: 5000, kind: 'PIX', counterpart: 'Padaria Pão Quente',
    cardLast4: null, date: '2026-11-05', description: 'Padaria Pão Quente', transactionId: null,
    createdAt: '2026-11-05T14:32:00', ...over,
  }
  db().put('bankNotification', rec)
  return rec
}

describe('caixa A confirmar', () => {
  beforeEach(async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 10, 5, 15))
    await freshStore()
  })
  afterEach(() => { vi.useRealTimers() })

  it('sugere conta, categoria e observação com o texto original; confirmar cria o lançamento', () => {
    const { account, expense } = basicCatalog()
    saveRule('padaria', expense.id)
    const n = notification()
    const [s] = listInbox()
    expect(s!.input.accountId).toBe(account.id)
    expect(s!.input.categoryId).toBe(expense.id)
    expect(s!.input.amount).toBe(50)
    expect(s!.input.notes).toBe('Via notificação · Nubank · 05/11 14:32\nPix enviado\nVocê enviou R$ 50,00 para Padaria Pão Quente')

    const tx = confirm(n.id)
    expect(requireTransaction(tx.id).amount).toBe(5000)
    expect(requireTransaction(tx.id).merchant).toBe('Padaria Pão Quente')
    expect(db().get('bankNotification', n.id)!.status).toBe('CONFIRMED')
    expect(db().get('bankNotification', n.id)!.transactionId).toBe(tx.id)
    expect(pendingCount()).toBe(0)
  })

  it('conta padrão do app do banco vinda do celular; cartão pelo final', () => {
    basicCatalog()
    const pj = createAccount({ name: 'Conta PJ', type: 'CHECKING', initialBalance: 0, color: '#123456' })
    const card = createAccount({ name: 'Nubank final 1234', type: 'CREDIT_CARD', initialBalance: 0, color: '#7c3aed', closingDay: 25, dueDay: 5 })
    notification({ defaultAccountId: pj.id, postedAt: 2 })
    notification({ kind: 'CARD', cardLast4: '1234', text: 'Compra de R$ 50,00 APROVADA em PADARIA', postedAt: 1 })
    const [pix, compra] = listInbox()
    expect(pix!.input.accountId).toBe(pj.id)
    expect(compra!.input.accountId).toBe(card.id)
  })

  it('avisa lançamento igual; descartar e salvar pelo formulário tiram da caixa', () => {
    const { account } = basicCatalog()
    createTransaction({ description: 'Padaria', amount: 50, type: 'EXPENSE', dueDate: '2026-11-04', paid: true, notes: null, accountId: account.id, categoryId: null })
    const a = notification()
    const b = notification({ hash: 'h2' })
    expect(listInbox()[0]!.duplicateOfDescription).toBe('Padaria')
    discard(a.id)
    markConfirmed(b.id, 'tx-1')
    expect(listInbox()).toHaveLength(0)
    expect(db().get('bankNotification', a.id)!.status).toBe('DISCARDED')
  })

  it('observação limitada a 500 caracteres', () => {
    expect(notesFor(notification({ text: 'x'.repeat(800) })).length).toBe(500)
  })
})
