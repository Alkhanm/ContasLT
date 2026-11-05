/**
 * Datas como texto `AAAA-MM-DD` e meses como `AAAA-MM`, com a mesma aritmética do java.time (LocalDate/YearMonth):
 * somar meses mantém o dia, limitado ao fim do mês (31/01 + 1 mês = 28 ou 29/02). Sem fuso: tudo é data civil.
 */
export type IsoDate = string
export type YearMonth = string

const pad = (n: number, w = 2) => String(n).padStart(w, '0')

export function ymd(y: number, m: number, d: number): IsoDate {
  return `${pad(y, 4)}-${pad(m)}-${pad(d)}`
}

export function parts(date: IsoDate): [number, number, number] {
  return [Number(date.slice(0, 4)), Number(date.slice(5, 7)), Number(date.slice(8, 10))]
}

export function isLeap(y: number) {
  return (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0
}

export function lengthOfMonth(y: number, m: number) {
  return [31, isLeap(y) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1]
}

/** Dias desde 1970-01-01 (como LocalDate.toEpochDay). */
export function toEpochDay(date: IsoDate): number {
  const [y, m, d] = parts(date)
  return Math.round(Date.UTC(y, m - 1, d) / 86_400_000)
}

export function fromEpochDay(day: number): IsoDate {
  const dt = new Date(day * 86_400_000)
  return ymd(dt.getUTCFullYear(), dt.getUTCMonth() + 1, dt.getUTCDate())
}

export function plusDays(date: IsoDate, n: number): IsoDate {
  return fromEpochDay(toEpochDay(date) + n)
}

export function plusWeeks(date: IsoDate, n: number): IsoDate {
  return plusDays(date, 7 * n)
}

export function plusMonths(date: IsoDate, n: number): IsoDate {
  const [y, m, d] = parts(date)
  const total = y * 12 + (m - 1) + n
  const ny = Math.floor(total / 12)
  const nm = (total % 12 + 12) % 12 + 1
  return ymd(ny, nm, Math.min(d, lengthOfMonth(ny, nm)))
}

export function plusYears(date: IsoDate, n: number): IsoDate {
  return plusMonths(date, 12 * n)
}

/** Diferença em dias (b − a), como ChronoUnit.DAYS.between(a, b). */
export function daysBetween(a: IsoDate, b: IsoDate) {
  return toEpochDay(b) - toEpochDay(a)
}

export function dayOfMonth(date: IsoDate) {
  return Number(date.slice(8, 10))
}

/** Data de hoje no fuso do aparelho. */
export function today(): IsoDate {
  const d = new Date()
  return ymd(d.getFullYear(), d.getMonth() + 1, d.getDate())
}

export const minDate = (a: IsoDate, b: IsoDate) => (a <= b ? a : b)
export const maxDate = (a: IsoDate, b: IsoDate) => (a >= b ? a : b)

// ---------- Meses ----------

export function monthOf(date: IsoDate): YearMonth {
  return date.slice(0, 7)
}

export function currentMonth(): YearMonth {
  return monthOf(today())
}

export function plusMonthsYm(month: YearMonth, n: number): YearMonth {
  return monthOf(plusMonths(`${month}-01`, n))
}

export function atDay(month: YearMonth, day: number): IsoDate {
  const [y, m] = [Number(month.slice(0, 4)), Number(month.slice(5, 7))]
  return ymd(y, m, Math.min(day, lengthOfMonth(y, m)))
}

export function firstDay(month: YearMonth): IsoDate {
  return `${month}-01`
}

export function endOfMonth(month: YearMonth): IsoDate {
  const [y, m] = [Number(month.slice(0, 4)), Number(month.slice(5, 7))]
  return ymd(y, m, lengthOfMonth(y, m))
}

export function monthLength(month: YearMonth) {
  return lengthOfMonth(Number(month.slice(0, 4)), Number(month.slice(5, 7)))
}

/** Meses de `from` a `to`, inclusive. */
export function monthsBetween(from: YearMonth, to: YearMonth): YearMonth[] {
  const out: YearMonth[] = []
  for (let m = from; m <= to && out.length < 1200; m = plusMonthsYm(m, 1)) out.push(m)
  return out
}

export function isDate(s: unknown): s is IsoDate {
  return typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s)
}

export function isMonth(s: unknown): s is YearMonth {
  return typeof s === 'string' && /^\d{4}-\d{2}$/.test(s)
}

/** Agora em ISO local sem fuso (como LocalDateTime.toString): createdAt dos registros. */
export function nowIso(): string {
  const d = new Date()
  return `${ymd(d.getFullYear(), d.getMonth() + 1, d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`
}
