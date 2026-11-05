/** Valores monetários: centavos inteiros nos registros e nas contas; reais (number) só nos DTOs da interface. */
export type Cents = number

export function toCents(reais: number | null | undefined): Cents {
  if (reais == null || !Number.isFinite(reais)) return 0
  return Math.round(reais * 100)
}

export function toCentsOrNull(reais: number | null | undefined): Cents | null {
  return reais == null ? null : toCents(reais)
}

export function fromCents(cents: Cents): number
export function fromCents(cents: Cents | null | undefined): number | null
export function fromCents(cents: Cents | null | undefined): number | null {
  return cents == null ? null : cents / 100
}

export const sum = (values: Iterable<Cents>) => {
  let s = 0
  for (const v of values) s += v
  return s
}
