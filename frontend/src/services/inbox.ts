import type { Id, Transaction, TransactionInput } from '@/api/types'
import type { AccountRec, BankNotificationRec, TransactionRec } from '@/data/schema'
import { daysBetween } from '@/domain/dates'
import { CategorySuggester } from '@/domain/categorySuggester'
import { fromCents } from '@/domain/money'
import { normalize } from '@/domain/text'
import { isCreditCard } from './cards'
import { BusinessError, db, require } from './context'
import { transactionDto } from './mappers'
import { matchPending, settle } from './recurring'
import { createTransaction, requireTransaction } from './transactions'

/**
 * Caixa "A confirmar": movimentos lidos das notificações dos bancos no celular (porte de
 * android/.../notify/InboxService.java). O app Android grava os registros `bankNotification`; aqui eles viram
 * sugestão de lançamento com as mesmas regras (conta pelo final do cartão ou pela conta padrão do app do banco,
 * categoria pelas regras, vencimento recorrente que o valor parece pagar e lançamento igual já existente).
 * Confirmar ou descartar aqui some do celular na próxima sincronização.
 */

export interface InboxSuggestion {
  id: Id
  source: string
  postedAt: number
  /** Texto original da notificação. */
  raw: string
  input: TransactionInput
  accountName: string | null
  categoryName: string | null
  categoryIcon: string | null
  categoryColor: string | null
  /** Vencimento recorrente pendente que este movimento parece pagar. */
  occurrenceId: Id | null
  occurrenceDescription: string | null
  occurrenceDate: string | null
  occurrenceAmount: number | null
  /** Lançamento que já parece ser este mesmo movimento. */
  duplicateOfId: Id | null
  duplicateOfDescription: string | null
  duplicateOfDate: string | null
}

export function pendingNotifications(): BankNotificationRec[] {
  return db().list('bankNotification').filter((n) => n.status === 'PENDING').sort((a, b) => b.postedAt - a.postedAt)
}

export function pendingCount(): number {
  return db().list('bankNotification').filter((n) => n.status === 'PENDING').length
}

export function listInbox(): InboxSuggestion[] {
  return pendingNotifications().map(suggest)
}

export function rawText(n: Pick<BankNotificationRec, 'title' | 'text'>): string {
  return `${n.title?.trim() ?? ''}\n${n.text?.trim() ?? ''}`.trim()
}

/** Observação do lançamento: origem, horário e o texto original. Mesmo formato do Android (InboxService.notes). */
export function notesFor(n: BankNotificationRec): string {
  let out = 'Via notificação'
  if (n.appLabel) out += ` · ${n.appLabel}`
  if (n.postedAt > 0) {
    const d = new Date(n.postedAt)
    const p = (x: number) => String(x).padStart(2, '0')
    out += ` · ${p(d.getDate())}/${p(d.getMonth() + 1)} ${p(d.getHours())}:${p(d.getMinutes())}`
  }
  const raw = rawText(n)
  if (raw) out += `\n${raw}`
  return out.length > 500 ? `${out.slice(0, 499)}…` : out
}

export function suggest(n: BankNotificationRec): InboxSuggestion {
  const s = db()
  const account = accountFor(n)
  const input: TransactionInput = {
    description: n.description,
    amount: fromCents(n.amount),
    type: n.type,
    dueDate: n.date,
    paid: true,
    notes: notesFor(n),
    accountId: account?.id ?? '',
    categoryId: null,
    merchant: n.counterpart,
  }
  const out: InboxSuggestion = {
    id: n.id, source: n.appLabel ?? n.packageName, postedAt: n.postedAt, raw: rawText(n), input,
    accountName: account?.name ?? null, categoryName: null, categoryIcon: null, categoryColor: null,
    occurrenceId: null, occurrenceDescription: null, occurrenceDate: null, occurrenceAmount: null,
    duplicateOfId: null, duplicateOfDescription: null, duplicateOfDate: null,
  }

  const category = new CategorySuggester(s.list('category'), s.list('categoryRule'))
    .suggest(n.counterpart ?? n.description, n.type, null)
  if (category) {
    input.categoryId = category.id
    out.categoryName = category.name
    out.categoryIcon = category.icon
    out.categoryColor = category.color
  }

  const cand = {
    description: `${n.counterpart ?? ''} ${n.title ?? ''} ${n.description}`,
    amount: n.amount, type: n.type, date: n.date,
  }
  const occ = matchPending([cand], (c) => c, n.date, n.date).get(cand)?.occurrence ?? null
  if (occ) {
    out.occurrenceId = occ.id
    out.occurrenceDescription = occ.description
    out.occurrenceDate = occ.dueDate
    out.occurrenceAmount = fromCents(occ.amount)
    if (!input.categoryId && occ.categoryId) {
      const oc = s.get('category', occ.categoryId)
      if (oc) {
        input.categoryId = oc.id
        out.categoryName = oc.name
        out.categoryIcon = oc.icon
        out.categoryColor = oc.color
      }
    }
  }

  const dup = duplicateOf(n, occ?.id ?? null)
  if (dup) {
    out.duplicateOfId = dup.id
    out.duplicateOfDescription = dup.description
    out.duplicateOfDate = dup.dueDate
  }
  return out
}

/** Confirma: paga o vencimento recorrente casado, ou cria o lançamento. */
export function confirm(id: Id): Transaction {
  const n = requireNotification(id)
  const sug = suggest(n)
  let tx: Transaction
  if (sug.occurrenceId) {
    const occ = settle(requireTransaction(sug.occurrenceId), n.amount, n.date)
    if (occ.merchant == null) occ.merchant = n.counterpart
    if (occ.categoryId == null) occ.categoryId = sug.input.categoryId
    if (sug.input.accountId) occ.accountId = sug.input.accountId
    db().put('transaction', occ)
    tx = transactionDto(occ)
  } else {
    if (!sug.input.accountId) throw new BusinessError('Escolha a conta: toque em Editar para completar o lançamento.')
    tx = createTransaction(sug.input).transaction
  }
  markConfirmed(id, tx.id)
  return tx
}

/** Lançamento salvo pelo formulário (Editar): a notificação sai da caixa. */
export function markConfirmed(id: Id, transactionId: Id) {
  setStatus(id, 'CONFIRMED', transactionId)
}

export function discard(id: Id) {
  setStatus(id, 'DISCARDED', null)
}

function setStatus(id: Id, status: BankNotificationRec['status'], transactionId: Id | null) {
  const n = requireNotification(id)
  db().put('bankNotification', { ...n, status, transactionId: transactionId ?? n.transactionId })
}

function requireNotification(id: Id): BankNotificationRec {
  return require(db().get('bankNotification', id), 'Notificação não encontrada')
}

/**
 * Conta do movimento. Compra no crédito: o cartão cujo nome tem o final lido, o cartão padrão do app ou o único
 * cartão cadastrado. O resto (Pix, débito, boleto, transferência): a conta padrão do app ou a primeira conta ativa.
 */
export function accountFor(n: BankNotificationRec): AccountRec | null {
  const s = db()
  const accounts = s.list('account').sort((a, b) => (a.createdAt < b.createdAt ? -1 : a.createdAt > b.createdAt ? 1 : 0))
  const cards = accounts.filter((a) => !a.archived && isCreditCard(a))
  const text = normalize(`${n.title ?? ''} ${n.text ?? ''}`)
  const credit = n.kind === 'CARD' && !text.includes('debito')
  if (credit) {
    if (n.cardLast4) {
      const byLast4 = cards.find((c) => c.name?.includes(n.cardLast4!))
      if (byLast4) return byLast4
    }
    const card = s.get('account', n.defaultCardId)
    if (card && !card.archived) return card
    if (cards.length === 1) return cards[0]!
  }
  const def = s.get('account', n.defaultAccountId)
  if (def && !def.archived) return def
  return accounts.find((a) => !a.archived && !isCreditCard(a)) ?? null
}

/** Mesmo tipo e valor, pago, até um dia de distância (ex.: já lançado à mão ou confirmado antes). */
function duplicateOf(n: BankNotificationRec, ignoreId: Id | null): TransactionRec | null {
  return db().list('transaction').find((t) => t.id !== ignoreId && t.type === n.type && t.amount === n.amount
    && t.transferId == null && t.paid && Math.abs(daysBetween(t.dueDate, n.date)) <= 1) ?? null
}
