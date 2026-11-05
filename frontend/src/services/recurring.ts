import type { RecurringBill, RecurringBillInput } from '@/api/types'
import type { Id, RecurringBillRec, TransactionRec } from '@/data/schema'
import { syncState } from '@/data/session'
import { currentMonth, endOfMonth, firstDay, monthOf, monthsBetween, nowIso, plusDays, type IsoDate, type YearMonth } from '@/domain/dates'
import { newId, occurrenceId } from '@/domain/ids'
import { keyTokens, MAX_DAYS, score, type Candidate } from '@/domain/matcher'
import { toCents } from '@/domain/money'
import { between, numberOf } from '@/domain/recurrence'
import { requireCategoryOrNull } from './catalog'
import { BusinessError, db, isTestStore, require, validate } from './context'
import { recurringDto } from './mappers'

/**
 * Contas recorrentes (porte de RecurringBillService). Os vencimentos de cada mês viram lançamentos na primeira vez
 * que o mês é visto ({@link ensureOccurrences}), com id determinístico por regra + data prevista: dois aparelhos
 * gerando o mesmo vencimento offline chegam ao mesmo registro.
 */

/** Meses já garantidos nesta sessão; esquecidos quando uma regra muda ou chegam dados de fora. */
const ensured = new Set<YearMonth>()

export function invalidateEnsured() {
  ensured.clear()
}

/**
 * Só gera vencimentos depois da primeira sincronização completa do aparelho: antes disso os vencimentos que já
 * existem no servidor ainda não chegaram e seriam gerados de novo.
 */
function canGenerate() {
  return isTestStore() || syncState.initialDone
}

export function listRecurring(): RecurringBill[] {
  const collator = new Intl.Collator('pt-BR')
  return db().list('recurringBill')
    .sort((a, b) => Number(b.active) - Number(a.active) || collator.compare(a.description, b.description))
    .map(recurringDto)
}

export function requireBill(id: Id): RecurringBillRec {
  return require(db().get('recurringBill', id), 'Conta recorrente não encontrada')
}

function check(req: RecurringBillInput) {
  validate([
    ['description', !!req.description?.trim(), 'Informe a descrição'],
    ['description', (req.description ?? '').trim().length <= 140, 'Descrição muito longa'],
    ['amount', req.amount != null, 'Informe o valor'],
    ['amount', req.amount == null || req.amount > 0, 'O valor deve ser maior que zero'],
    ['type', !!req.type, 'Informe o tipo'],
    ['frequency', !!req.frequency, 'Informe a frequência'],
    ['startDate', !!req.startDate, 'Informe a data do primeiro vencimento'],
    ['occurrences', req.occurrences == null || req.occurrences >= 2, 'Parcelamento precisa de pelo menos 2 parcelas'],
    ['occurrences', req.occurrences == null || req.occurrences <= 600, 'Máximo de 600 parcelas'],
    ['accountId', req.accountId != null, 'Escolha a conta'],
    ['notes', (req.notes ?? '').length <= 500, 'Observação muito longa'],
    ['matchStrictness', req.matchStrictness == null || (req.matchStrictness >= 1 && req.matchStrictness <= 10), 'Precisão entre 1 e 10'],
  ])
  if (req.endDate && req.endDate < req.startDate) {
    throw new BusinessError('A data final não pode ser anterior ao primeiro vencimento')
  }
}

function applyBill(bill: RecurringBillRec, req: RecurringBillInput) {
  require(db().get('account', req.accountId), 'Conta não encontrada')
  const category = requireCategoryOrNull(req.categoryId)
  if (category && category.type !== req.type) {
    throw new BusinessError('A categoria escolhida não é do mesmo tipo do lançamento')
  }
  bill.description = req.description.trim()
  bill.amount = toCents(req.amount)
  bill.type = req.type
  bill.frequency = req.frequency
  bill.startDate = req.startDate
  bill.endDate = req.endDate ?? null
  bill.occurrences = req.occurrences ?? null
  bill.accountId = req.accountId
  bill.categoryId = category?.id ?? null
  bill.notes = req.notes ?? null
  if (req.active != null) bill.active = req.active
  if (req.matchStrictness != null) bill.matchStrictness = req.matchStrictness
}

export function createRecurring(req: RecurringBillInput): RecurringBill {
  invalidateEnsured()
  check(req)
  const bill: RecurringBillRec = {
    id: newId(), accountId: null, categoryId: null, description: '', amount: 0, type: 'EXPENSE', frequency: 'MONTHLY',
    startDate: req.startDate, endDate: null, occurrences: null, startNumber: 1, matchStrictness: 5, notes: null,
    active: true, createdAt: nowIso(),
  }
  applyBill(bill, req)
  db().put('recurringBill', bill)
  if (req.fromTransactionId != null) adopt(bill, req.fromTransactionId)
  ensureOccurrences(currentMonth())
  return recurringDto(bill)
}

/**
 * Lançamento existente vira ocorrência da regra: mantém data, valor e situação de pago; só passa a contar como
 * vencimento dela, então não é gerado outro na mesma data.
 */
function adopt(bill: RecurringBillRec, transactionId: Id) {
  const t = require(db().get('transaction', transactionId), 'Lançamento não encontrado')
  if (t.recurringBillId) throw new BusinessError('Este lançamento já pertence a uma conta recorrente')
  if (t.transferId) throw new BusinessError('Transferência entre contas não vira conta recorrente')
  if (t.type !== bill.type) throw new BusinessError('O tipo da recorrência precisa ser o mesmo do lançamento')
  db().put('transaction', {
    ...t,
    recurringBillId: bill.id,
    scheduledDate: t.dueDate,
    installmentNumber: bill.occurrences != null && t.dueDate === bill.startDate ? 1 : null,
  })
}

const occurrencesOf = (billId: Id) => db().list('transaction').filter((t) => t.recurringBillId === billId)

function syncOccurrence(bill: RecurringBillRec, t: TransactionRec): TransactionRec {
  return {
    ...t,
    description: bill.description,
    amount: bill.amount,
    type: bill.type,
    accountId: bill.accountId ?? t.accountId,
    categoryId: bill.categoryId,
  }
}

const sameOccurrence = (a: TransactionRec, b: TransactionRec) =>
  a.description === b.description && a.amount === b.amount && a.type === b.type && a.accountId === b.accountId
  && a.categoryId === b.categoryId

/**
 * "Editar este e os próximos": `t` já recebeu as alterações; aqui elas passam para a regra e para as ocorrências
 * pendentes previstas depois dele. Se a data mudou, a regra é reancorada nesse vencimento e os próximos são gerados
 * de novo a partir daí. Ocorrências anteriores ficam como estão. Devolve o lançamento como ficou.
 */
export function applyToFollowing(t: TransactionRec, previousScheduled: IsoDate): TransactionRec {
  invalidateEnsured()
  const s = db()
  const bill = structuredClone(requireBill(t.recurringBillId!))
  if (t.type !== bill.type) throw new BusinessError('Para mudar o tipo, edite a conta recorrente')
  bill.description = t.description
  bill.amount = t.amount
  bill.accountId = t.accountId
  bill.categoryId = t.categoryId

  const newDate = t.dueDate
  if (newDate === previousScheduled) {
    s.put('recurringBill', bill)
    for (const next of occurrencesOf(bill.id)) {
      if (next.paid || !next.scheduledDate || next.scheduledDate <= previousScheduled || next.id === t.id) continue
      const synced = syncOccurrence(bill, next)
      if (!sameOccurrence(synced, next)) s.put('transaction', synced)
    }
    return t
  }
  if (occurrencesOf(bill.id).some((o) => o.scheduledDate === newDate && o.id !== t.id)) {
    throw new BusinessError('Já existe outro vencimento desta conta recorrente previsto para essa data')
  }
  const number = t.installmentNumber ?? numberOf(bill, previousScheduled)
  for (const o of occurrencesOf(bill.id)) {
    if (!o.paid && o.scheduledDate && o.scheduledDate > previousScheduled && o.id !== t.id) s.remove('transaction', o.id)
  }
  if (number != null) bill.startNumber = number
  bill.startDate = newDate
  s.put('recurringBill', bill)
  const moved = { ...t, scheduledDate: newDate }
  s.put('transaction', moved)
  ensureOccurrences(monthOf(newDate))
  return moved
}

export function updateRecurring(id: Id, req: RecurringBillInput): RecurringBill {
  invalidateEnsured()
  check(req)
  const s = db()
  const bill = structuredClone(requireBill(id))
  const scheduleChanged = bill.frequency !== req.frequency || bill.startDate !== req.startDate
    || (bill.endDate ?? null) !== (req.endDate ?? null) || (bill.occurrences ?? null) !== (req.occurrences ?? null)
  applyBill(bill, req)
  s.put('recurringBill', bill)
  for (const t of occurrencesOf(id)) {
    if (t.paid) continue
    if (scheduleChanged || !bill.active) {
      // Pendentes são derivados da regra: recriados conforme o novo calendário (ou some, se a regra parou).
      s.remove('transaction', t.id)
    } else {
      const synced = syncOccurrence(bill, t)
      if (!sameOccurrence(synced, t)) s.put('transaction', synced)
    }
  }
  ensureOccurrences(currentMonth())
  return recurringDto(bill)
}

export function deleteRecurring(id: Id) {
  invalidateEnsured()
  const s = db()
  requireBill(id)
  for (const t of occurrencesOf(id)) {
    if (!t.paid) s.remove('transaction', t.id)
    else s.put('transaction', { ...t, recurringBillId: null })
  }
  s.remove('recurringBill', id)
}

/**
 * Garante que todos os vencimentos das regras ativas no mês existam como lançamentos. Idempotente: a chave é
 * (regra, data prevista), e o id do vencimento é derivado dela.
 */
export function ensureOccurrences(month: YearMonth) {
  if (ensured.has(month) || !canGenerate()) return
  const s = db()
  const active = s.list('recurringBill').filter((b) => b.active && b.accountId && s.get('account', b.accountId))
  if (active.length) {
    const from = firstDay(month)
    const to = endOfMonth(month)
    const existing = new Set<string>()
    for (const t of s.list('transaction')) {
      if (t.recurringBillId && t.scheduledDate && t.scheduledDate >= from && t.scheduledDate <= to) {
        existing.add(`${t.recurringBillId}|${t.scheduledDate}`)
      }
    }
    for (const bill of active) {
      for (const occ of between(bill, from, to)) {
        if (existing.has(`${bill.id}|${occ.date}`)) continue
        const id = occurrenceId(bill.id, occ.date)
        // Um vencimento com este id que existe e foi movido para outra data prevista continua sendo ele.
        if (s.get('transaction', id)) continue
        s.put('transaction', syncOccurrence(bill, {
          id, accountId: bill.accountId!, categoryId: null, recurringBillId: bill.id, importBatchId: null,
          transferId: null, description: '', amount: 0, type: bill.type, dueDate: occ.date, scheduledDate: occ.date,
          paid: false, notes: null, externalId: null,
          installmentNumber: bill.occurrences == null ? null : occ.number,
          merchant: null, receiptFile: null, receiptAccessKey: null, linkedImport: false, receiptScanStatus: null,
          receiptScanTotal: null, receiptScanDate: null, cardAccountId: null, cardInvoiceMonth: null, items: [],
          createdAt: nowIso(),
        }), { generated: true })
      }
    }
  }
  ensured.add(month)
}

/** Garante os vencimentos de todos os meses que tocam o intervalo. */
export function ensureRange(from: IsoDate, to: IsoDate) {
  for (const m of monthsBetween(monthOf(from), monthOf(to))) ensureOccurrences(m)
}

export interface ScoredOccurrence {
  occurrence: TransactionRec
  score: number
}

/**
 * Melhor vencimento pendente para cada candidato lido (extrato ou comprovante). Antes garante os vencimentos dos
 * meses do período. Cada ocorrência casa com no máximo um candidato.
 */
export function matchPending<C>(candidates: C[], toCandidate: (c: C) => Candidate, from: IsoDate, to: IsoDate): Map<C, ScoredOccurrence> {
  ensureRange(from, to)
  const s = db()
  const lo = plusDays(from, -MAX_DAYS)
  const hi = plusDays(to, MAX_DAYS)
  const txs = s.list('transaction')
  const pending = txs.filter((t) => {
    if (!t.recurringBillId || t.paid || t.importBatchId || t.externalId || t.dueDate < lo || t.dueDate > hi) return false
    return s.get('recurringBill', t.recurringBillId)?.active === true
  })
  const result = new Map<C, ScoredOccurrence>()
  if (!pending.length) return result

  const keysByBill = new Map<Id, Set<string>>()
  for (const occ of pending) {
    const billId = occ.recurringBillId!
    if (keysByBill.has(billId)) continue
    const bill = s.get('recurringBill', billId)!
    const history = txs.filter((t) => t.recurringBillId === billId && t.paid)
      .sort((a, b) => (a.dueDate < b.dueDate ? 1 : a.dueDate > b.dueDate ? -1 : 0))
      .slice(0, 6)
      .map((t) => `${t.merchant ?? ''} ${t.description}`)
    keysByBill.set(billId, keyTokens(bill, history))
  }

  const pairs: { c: C; occ: TransactionRec; score: number }[] = []
  for (const c of candidates) {
    const cand = toCandidate(c)
    for (const occ of pending) {
      const sc = score(occ, s.get('recurringBill', occ.recurringBillId), keysByBill.get(occ.recurringBillId!)!, cand)
      if (sc > 0) pairs.push({ c, occ, score: sc })
    }
  }
  pairs.sort((a, b) => b.score - a.score)
  const used = new Set<Id>()
  for (const p of pairs) {
    if (result.has(p.c) || used.has(p.occ.id)) continue
    result.set(p.c, { occurrence: p.occ, score: p.score })
    used.add(p.occ.id)
  }
  return result
}

/** O vencimento passa a representar o pagamento real: valor e data do extrato/comprovante, data prevista guardada. */
export function settle(occurrence: TransactionRec, amount: number, date: IsoDate): TransactionRec {
  return { ...occurrence, amount, dueDate: date, paid: true }
}
