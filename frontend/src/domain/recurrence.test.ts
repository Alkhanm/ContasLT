import { describe, expect, it } from 'vitest'
import type { Frequency } from '@/data/schema'
import { between, nextOnOrAfter, nth, numberOf } from './recurrence'

/** Porte de RecurrenceCalculatorTest. */

function bill(frequency: Frequency, startDate: string, occurrences: number | null, endDate: string | null) {
  return { frequency, startDate, occurrences, endDate, startNumber: 1 }
}

describe('RecurrenceCalculator', () => {
  it('mensal no dia 31 fica no último dia dos meses mais curtos', () => {
    const b = bill('MONTHLY', '2026-01-31', null, null)
    expect(between(b, '2026-02-01', '2026-02-28').map((o) => o.date)).toEqual(['2026-02-28'])
    expect(between(b, '2026-03-01', '2026-03-31').map((o) => o.date)).toEqual(['2026-03-31'])
  })

  it('parcelamento para depois da última parcela', () => {
    const b = bill('MONTHLY', '2026-01-10', 3, null)
    const all = between(b, '2025-01-01', '2027-12-31')
    expect(all.map((o) => o.number)).toEqual([1, 2, 3])
    expect(all[2].date).toBe('2026-03-10')
  })

  it('semanal gera vários vencimentos no mês', () => {
    const b = bill('WEEKLY', '2026-10-01', null, null)
    expect(between(b, '2026-10-01', '2026-10-31')).toHaveLength(5)
  })

  it('respeita a data final e calcula o próximo vencimento', () => {
    const b = bill('MONTHLY', '2026-01-05', null, '2026-03-31')
    expect(between(b, '2026-04-01', '2026-04-30')).toEqual([])
    expect(nextOnOrAfter(b, '2026-02-06')).toBe('2026-03-05')
    expect(nextOnOrAfter(b, '2026-04-01')).toBeNull()
  })

  // Extras: aritmética de datas das outras frequências e o número de uma ocorrência reancorada.
  it('frequências sem deslizar o dia do mês', () => {
    expect(nth('BIWEEKLY', '2026-01-01', 2)).toBe('2026-01-29')
    expect(nth('BIMONTHLY', '2026-01-31', 1)).toBe('2026-03-31')
    expect(nth('QUARTERLY', '2025-11-30', 1)).toBe('2026-02-28')
    expect(nth('SEMIANNUAL', '2026-08-31', 1)).toBe('2027-02-28')
    expect(nth('YEARLY', '2024-02-29', 1)).toBe('2025-02-28')
    expect(nth('YEARLY', '2024-02-29', 4)).toBe('2028-02-29')
    expect(nth('MONTHLY', '2026-01-31', 2)).toBe('2026-03-31')
  })

  it('numberOf considera o número inicial da regra', () => {
    const b = { ...bill('MONTHLY', '2034-03-01', 6, null), startNumber: 3 }
    expect(numberOf(b, '2034-04-01')).toBe(4)
    expect(numberOf(b, '2034-04-02')).toBeNull()
    expect(between(b, '2034-01-01', '2034-12-31').map((o) => o.number)).toEqual([3, 4, 5, 6])
  })
})
