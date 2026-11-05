import type { Transfer, TransferInput, TransferSuggestion, TransferSuggestionList } from '@/api/types'
import type { AccountRec, Id, TransactionRec, TransactionType, TransferRec, TransferRejectionRec } from '@/data/schema'
import { daysBetween, nowIso, plusMonths, today, type IsoDate } from '@/domain/dates'
import { newId } from '@/domain/ids'
import { toCents } from '@/domain/money'
import { normalize } from '@/domain/text'
import { isCreditCard } from './cards'
import { BusinessError, db, require, validate } from './context'
import { transactionDto } from './mappers'
import { emptyTransaction, requireTransaction } from './transactions'

/**
 * Transferências entre contas do mesmo usuário (porte de TransferService). Cada transferência é um par de
 * lançamentos (despesa na origem, receita no destino) vinculados por um registro `transfer` e pelo `transferId` nas
 * duas pernas; eles continuam valendo para o saldo de cada conta, mas são ignorados em receitas, despesas e
 * categorias, por serem só movimentação interna. Cartão de crédito fica de fora: o pagamento da fatura é uma
 * despesa comum ligada ao cartão.
 */

/** Janela máxima entre a saída e a entrada para o cruzamento automático sugerir o par. */
export const MAX_DAYS_APART = 3
/** Quanto do histórico é vasculhado em busca de pares. */
const LOOKBACK_MONTHS = 18
const TRANSFER_WORDS = ['transf', 'pix', 'ted', 'doc ', 'aplicacao', 'resgate', 'envio', 'enviad', 'recebid', 'entre contas']

// ---------- Recusas ("não é transferência") ----------

/** Ocorrência de conta recorrente vira `bill:<id>` (a recusa cobre todos os meses); lançamento avulso, `tx:<id>`. */
function keyOf(t: TransactionRec): string {
  const bill = db().get('recurringBill', t.recurringBillId)
  return bill ? `bill:${bill.id}` : `tx:${t.id}`
}

/** Chave do par, independente da ordem. */
function pairKey(a: string, b: string) {
  return a <= b ? `${a}|${b}` : `${b}|${a}`
}

const coversRecurring = (r: Pick<TransferRejectionRec, 'aKey' | 'bKey'>) =>
  r.aKey.startsWith('bill:') && r.bKey.startsWith('bill:')

/**
 * "Não é transferência": o par some das sugestões. Se os dois lançamentos são ocorrências de contas recorrentes, a
 * recusa vale para aquelas duas regras em todos os meses (por isso basta dizer uma vez). Devolve se a recusa cobre
 * as regras recorrentes.
 */
export function rejectTransfer(transactionId: Id, otherTransactionId: Id): { recurring: boolean } {
  const a = requireTransaction(transactionId)
  const b = requireTransaction(otherTransactionId)
  if (a.id === b.id) throw new BusinessError('Escolha dois lançamentos diferentes')
  const ka = keyOf(a)
  const kb = keyOf(b)
  const [aKey, bKey] = ka <= kb ? [ka, kb] : [kb, ka]
  const s = db()
  if (!s.list('transferRejection').some((r) => r.aKey === aKey && r.bKey === bKey)) {
    s.put('transferRejection', { id: newId(), aKey, bKey, createdAt: nowIso() })
  }
  return { recurring: coversRecurring({ aKey, bKey }) }
}

/** Esquece todas as recusas: os pares voltam a ser sugeridos. */
export function forgetRejections(): void {
  const s = db()
  for (const r of s.list('transferRejection')) s.remove('transferRejection', r.id)
}

// ---------- Criação, vínculo e desvínculo ----------

function requireAccount(id: Id): AccountRec {
  return require(db().get('account', id), 'Conta não encontrada')
}

export function createTransfer(req: TransferInput): Transfer {
  validate([
    ['fromAccountId', req.fromAccountId != null, 'Escolha a conta de origem'],
    ['toAccountId', req.toAccountId != null, 'Escolha a conta de destino'],
    ['amount', req.amount != null, 'Informe o valor'],
    ['amount', req.amount == null || req.amount > 0, 'O valor deve ser maior que zero'],
    ['date', !!req.date, 'Informe a data'],
    ['description', (req.description ?? '').length <= 140, 'Descrição muito longa'],
  ])
  if (req.fromAccountId === req.toAccountId) {
    throw new BusinessError('Origem e destino precisam ser contas diferentes')
  }
  const from = requireAccount(req.fromAccountId)
  const to = requireAccount(req.toAccountId)
  if (isCreditCard(from) || isCreditCard(to)) {
    throw new BusinessError('Cartão de crédito não entra em transferência: registre a fatura como despesa '
      + 'ligada ao cartão (pagamento de fatura)')
  }
  const description = req.description == null || req.description.trim() === '' ? 'Transferência' : req.description.trim()
  const paid = req.paid == null || req.paid
  const amount = toCents(req.amount)

  const out = leg(from, 'EXPENSE', amount, req.date, description, paid)
  const into = leg(to, 'INCOME', amount, req.date, description, paid)
  return toDto(link(out, into))
}

/** Vincula dois lançamentos existentes; a ordem informada não importa, a despesa vira a origem. */
export function linkTransfer(transactionId: Id, otherTransactionId: Id): Transfer {
  const a = requireTransaction(transactionId)
  const b = requireTransaction(otherTransactionId)
  if (a.id === b.id) throw new BusinessError('Escolha dois lançamentos diferentes')
  if (a.type === b.type) {
    throw new BusinessError('Uma transferência precisa de uma saída (despesa) e uma entrada (receita)')
  }
  const out = a.type === 'EXPENSE' ? a : b
  const into = out === a ? b : a
  validatePair(out, into)
  return toDto(link(out, into))
}

/** Desfaz o vínculo; os dois lançamentos continuam existindo como despesa e receita comuns. */
export function unlinkTransfer(id: Id): void {
  const s = db()
  const t = require(s.get('transfer', id), 'Transferência não encontrada')
  for (const legId of [t.fromTransactionId, t.toTransactionId]) {
    const leg = s.get('transaction', legId)
    if (leg && leg.transferId === t.id) s.put('transaction', { ...leg, transferId: null })
  }
  s.remove('transfer', t.id)
}

function validatePair(out: TransactionRec, into: TransactionRec) {
  const s = db()
  const outAccount = s.get('account', out.accountId)
  const inAccount = s.get('account', into.accountId)
  if (isCreditCard(outAccount) || isCreditCard(inAccount)) {
    throw new BusinessError('Compras de cartão não entram em transferência; a fatura é uma despesa ligada ao cartão')
  }
  if (out.transferId || into.transferId) {
    throw new BusinessError('Um dos lançamentos já faz parte de outra transferência')
  }
  if (out.accountId === into.accountId) {
    throw new BusinessError('A saída e a entrada precisam estar em contas diferentes')
  }
  if (out.amount !== into.amount) {
    throw new BusinessError('Os dois lançamentos precisam ter o mesmo valor')
  }
}

function link(out: TransactionRec, into: TransactionRec): TransferRec {
  const s = db()
  const t: TransferRec = { id: newId(), fromTransactionId: out.id, toTransactionId: into.id, createdAt: nowIso() }
  s.put('transfer', t)
  // Transferência não é despesa nem receita: perde a categoria para não poluir os relatórios.
  s.put('transaction', { ...out, transferId: t.id, categoryId: null })
  s.put('transaction', { ...into, transferId: t.id, categoryId: null })
  return t
}

function leg(account: AccountRec, type: TransactionType, amount: number, date: IsoDate, description: string, paid: boolean) {
  const t = emptyTransaction()
  t.accountId = account.id
  t.type = type
  t.amount = amount
  t.dueDate = date
  t.description = description
  t.paid = paid
  db().put('transaction', t)
  return t
}

function toDto(t: TransferRec): Transfer {
  const s = db()
  return {
    id: t.id,
    from: transactionDto(s.get('transaction', t.fromTransactionId)!),
    to: transactionDto(s.get('transaction', t.toTransactionId)!),
  }
}

// ---------- Cruzamento automático ----------

/** Ordem de criação (o id sequencial do backend): createdAt e, no empate, o id. */
function byCreation(a: TransactionRec, b: TransactionRec) {
  return a.createdAt < b.createdAt ? -1 : a.createdAt > b.createdAt ? 1 : a.id < b.id ? -1 : a.id > b.id ? 1 : 0
}

/** Lançamentos sem vínculo de transferência desde `from`, fora de cartão, por data e criação (findUnlinkedSince). */
function unlinkedSince(from: IsoDate): TransactionRec[] {
  const s = db()
  return s.list('transaction')
    .filter((t) => {
      if (t.transferId || t.dueDate < from) return false
      const a = s.get('account', t.accountId)
      return !!a && !isCreditCard(a)
    })
    .sort((a, b) => (a.dueDate < b.dueDate ? -1 : a.dueDate > b.dueDate ? 1 : byCreation(a, b)))
}

/**
 * Cruzamento automático: procura despesas e receitas de mesmo valor, em contas diferentes, com até
 * {@link MAX_DAYS_APART} dias de distância. Cada lançamento entra em no máximo um par; em caso de empate, vence o
 * par com datas mais próximas e descrição mais parecida com uma transferência. `userName`: nome do usuário logado
 * (descrições que o citam ganham pontos), que no aparelho vem da sessão, não dos dados.
 */
export function transferSuggestions(userName?: string | null): TransferSuggestionList {
  const s = db()
  const pool = unlinkedSince(plusMonths(today(), -LOOKBACK_MONTHS))
  const outs = pool.filter((t) => t.type === 'EXPENSE')
  const ins = pool.filter((t) => t.type === 'INCOME')
  const name = normalize(userName)
  const rejections = s.list('transferRejection')
  const rejected = new Set(rejections.map((r) => `${r.aKey}|${r.bKey}`))
  const keys = new Map<Id, string>()
  const keyFor = (t: TransactionRec) => {
    let k = keys.get(t.id)
    if (k == null) keys.set(t.id, (k = keyOf(t)))
    return k
  }
  const accountName = (id: Id) => s.get('account', id)?.name ?? ''

  interface Candidate { out: TransactionRec; into: TransactionRec; days: number; score: number }
  const candidates: Candidate[] = []
  for (const out of outs) {
    for (const into of ins) {
      if (out.accountId === into.accountId || out.amount !== into.amount) continue
      const days = Math.abs(daysBetween(out.dueDate, into.dueDate))
      if (days > MAX_DAYS_APART || rejected.has(pairKey(keyFor(out), keyFor(into)))) continue
      const score = 100 - days * 15
        + hint(out, accountName(into.accountId), name) + hint(into, accountName(out.accountId), name)
        + (out.paid === into.paid ? 5 : 0)
      candidates.push({ out, into, days, score })
    }
  }
  candidates.sort((a, b) => b.score - a.score
    || (a.out.dueDate < b.out.dueDate ? -1 : a.out.dueDate > b.out.dueDate ? 1 : 0)
    || byCreation(a.out, b.out))

  const used = new Set<Id>()
  const items: TransferSuggestion[] = []
  for (const c of candidates) {
    if (used.has(c.out.id) || used.has(c.into.id)) continue
    used.add(c.out.id)
    used.add(c.into.id)
    items.push({ from: transactionDto(c.out), to: transactionDto(c.into), daysApart: c.days, score: c.score })
  }
  items.sort((a, b) => (a.from.dueDate < b.from.dueDate ? 1 : a.from.dueDate > b.from.dueDate ? -1 : 0))
  return { items, rejected: rejections.length }
}

/** Pontos extras quando a descrição parece uma transferência ou cita a outra conta / o próprio usuário. */
function hint(t: TransactionRec, otherAccountName: string, userName: string) {
  const d = normalize(t.description)
  let score = 0
  if (TRANSFER_WORDS.some((w) => d.includes(w))) score += 20
  const otherName = normalize(otherAccountName)
  if (otherName.length >= 3 && d.includes(otherName)) score += 15
  if (userName.length >= 3 && d.includes(userName)) score += 10
  return score
}
