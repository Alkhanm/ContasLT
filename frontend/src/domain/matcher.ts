import type { RecurringBillRec, TransactionRec, TransactionType } from '@/data/schema'
import { daysBetween, type IsoDate } from './dates'
import type { Cents } from './money'
import { normalize } from './text'

/**
 * Decide se uma linha de extrato (ou um comprovante) é o pagamento de um vencimento pendente de conta recorrente
 * (porte de OccurrenceMatcher). A chave forte é o nome: alguma palavra significativa em comum com a regra ou com
 * os pagamentos anteriores dela. Data e valor variam dentro da tolerância do nível de precisão da regra (1 a 10).
 */

export interface Candidate {
  description: string
  amount: Cents | null
  type: TransactionType
  date: IsoDate | null
}

export interface Tolerance {
  /** Diferença máxima de valor em %, ou -1 = qualquer. */
  amountPercent: number
  days: number
}

export const DEFAULT_LEVEL = 5
/** Maior janela de dias possível. */
export const MAX_DAYS = 45

const STOP_WORDS = new Set([
  'pagamento', 'pagto', 'pgto', 'pag', 'boleto', 'efetuado', 'efetuada', 'compra', 'cartao', 'debito', 'credito',
  'pix', 'enviado', 'enviada', 'recebido', 'recebida', 'transferencia', 'transf', 'ted', 'doc', 'ltda', 'eireli',
  'epp', 'com', 'para', 'pelo', 'pela', 'dos', 'das', 'por', 'via', 'ref', 'referente', 'fatura', 'conta',
  'aut', 'automatico', 'automatica', 'deb', 'cred', 'lancamento', 'servicos', 'servico', 'mensalidade',
])

export function toleranceFor(level: number): Tolerance {
  switch (Math.max(1, Math.min(10, level))) {
    case 1: return { amountPercent: -1, days: MAX_DAYS }
    case 2: return { amountPercent: 75, days: 35 }
    case 3: return { amountPercent: 50, days: 28 }
    case 4: return { amountPercent: 40, days: 21 }
    case 5: return { amountPercent: 30, days: 15 }
    case 6: return { amountPercent: 20, days: 10 }
    case 7: return { amountPercent: 12, days: 7 }
    case 8: return { amountPercent: 6, days: 4 }
    case 9: return { amountPercent: 2, days: 2 }
    default: return { amountPercent: 0, days: 0 }
  }
}

/** Palavras significativas (3+ letras, fora termos bancários genéricos). */
export function tokens(text: string | null | undefined): Set<string> {
  const out = new Set<string>()
  for (const t of normalize(text).split(' ')) if (t.length >= 3 && !STOP_WORDS.has(t)) out.add(t)
  return out
}

/** Palavras-chave da regra: nome dela mais descrições/estabelecimentos dos pagamentos anteriores. */
export function keyTokens(bill: Pick<RecurringBillRec, 'description'>, history: Iterable<string>): Set<string> {
  const keys = tokens(bill.description)
  for (const h of history) for (const t of tokens(h)) keys.add(t)
  return keys
}

/** Pontuação do casamento entre um vencimento pendente e o candidato; 0 = não casa. */
export function score(
  occurrence: Pick<TransactionRec, 'amount' | 'dueDate'>,
  bill: Pick<RecurringBillRec, 'type' | 'matchStrictness'> | null,
  keys: Set<string>,
  c: Candidate,
): number {
  if (!bill || c.type !== bill.type || c.amount == null || c.date == null) return 0
  const candidateTokens = tokens(c.description)
  let hits = 0
  for (const k of keys) if (candidateTokens.has(k)) hits++
  if (hits === 0) return 0
  const tol = toleranceFor(bill.matchStrictness)
  const diffPercent = amountDiffPercent(occurrence.amount, c.amount)
  if (tol.amountPercent >= 0 && diffPercent > tol.amountPercent) return 0
  const days = Math.abs(daysBetween(occurrence.dueDate, c.date))
  if (days > tol.days) return 0
  return Math.min(hits, 3) * 30 + Math.max(0, 60 - diffPercent * 2) + Math.max(0, 50 - days * 2)
}

/** Diferença percentual (inteira, arredondada para cima) entre o previsto e o lido. */
export function amountDiffPercent(expected: Cents | null, actual: Cents | null): number {
  if (expected == null || expected === 0) return actual == null || actual === 0 ? 0 : 100
  const diff = Math.abs((actual ?? 0) - expected) * 100
  return Math.ceil(diff / Math.abs(expected))
}
