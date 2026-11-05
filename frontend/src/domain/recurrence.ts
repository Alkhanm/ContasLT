import type { Frequency, RecurringBillRec } from '@/data/schema'
import { plusMonths, plusWeeks, plusYears, type IsoDate } from './dates'

/** Calcula as datas de vencimento de uma conta recorrente (porte de RecurrenceCalculator/Frequency). */

const MAX_ITERATIONS = 5000

type Schedule = Pick<RecurringBillRec, 'frequency' | 'startDate' | 'endDate' | 'occurrences' | 'startNumber'>

/** Data da ocorrência de índice n (0 = data inicial), sem drift de dia do mês. */
export function nth(frequency: Frequency, start: IsoDate, n: number): IsoDate {
  switch (frequency) {
    case 'WEEKLY': return plusWeeks(start, n)
    case 'BIWEEKLY': return plusWeeks(start, 2 * n)
    case 'MONTHLY': return plusMonths(start, n)
    case 'BIMONTHLY': return plusMonths(start, 2 * n)
    case 'QUARTERLY': return plusMonths(start, 3 * n)
    case 'SEMIANNUAL': return plusMonths(start, 6 * n)
    case 'YEARLY': return plusYears(start, n)
  }
}

export interface Occurrence {
  date: IsoDate
  /** Número sequencial (1 = primeira). */
  number: number
}

export function between(bill: Schedule, from: IsoDate, to: IsoDate): Occurrence[] {
  const result: Occurrence[] = []
  for (let i = 0; i < MAX_ITERATIONS; i++) {
    const number = bill.startNumber + i
    if (bill.occurrences != null && number > bill.occurrences) break
    const date = nth(bill.frequency, bill.startDate, i)
    if (date > to || (bill.endDate != null && date > bill.endDate)) break
    if (date >= from) result.push({ date, number })
  }
  return result
}

/** Número da ocorrência prevista para a data, ou null se a regra não cai nela. */
export function numberOf(bill: Schedule, date: IsoDate): number | null {
  for (let i = 0; i < MAX_ITERATIONS; i++) {
    const d = nth(bill.frequency, bill.startDate, i)
    if (d > date) return null
    if (d === date) return bill.startNumber + i
  }
  return null
}

/** Próxima data igual ou posterior à referência, ou null se a recorrência já terminou. */
export function nextOnOrAfter(bill: Schedule, reference: IsoDate): IsoDate | null {
  for (let i = 0; i < MAX_ITERATIONS; i++) {
    if (bill.occurrences != null && bill.startNumber + i > bill.occurrences) return null
    const date = nth(bill.frequency, bill.startDate, i)
    if (bill.endDate != null && date > bill.endDate) return null
    if (date >= reference) return date
  }
  return null
}
