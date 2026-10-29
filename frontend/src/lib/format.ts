import type { AccountType, Frequency, Transaction, InvoiceStatus } from '@/api/types'

const brl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })
const brlPlain = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export function money(value: number | null | undefined): string {
  return brl.format(value ?? 0)
}

export function moneyPlain(value: number | null | undefined): string {
  return brlPlain.format(value ?? 0)
}

/** Valor com sinal segundo o tipo: despesas negativas, receitas positivas. */
export function signedMoney(amount: number, type: 'EXPENSE' | 'INCOME'): string {
  return (type === 'EXPENSE' ? '−' : '+') + money(Math.abs(amount))
}

export function parseLocalDate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(y!, (m ?? 1) - 1, d ?? 1)
}

export function toIsoDate(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

export function todayIso(): string {
  return toIsoDate(new Date())
}

export function currentMonth(): string {
  return todayIso().slice(0, 7)
}

export function shiftMonth(month: string, delta: number): string {
  const [y, m] = month.split('-').map(Number)
  const date = new Date(y!, (m ?? 1) - 1 + delta, 1)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

const monthLong = new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric' })
const monthShort = new Intl.DateTimeFormat('pt-BR', { month: 'short' })
const dayShort = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'short' })
const dayLong = new Intl.DateTimeFormat('pt-BR', { weekday: 'short', day: 'numeric', month: 'long' })
const dayNumeric = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric' })

function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1)
}

export function monthLabel(month: string): string {
  return capitalize(monthLong.format(parseLocalDate(month + '-01')))
}

export function monthShortLabel(month: string): string {
  return capitalize(monthShort.format(parseLocalDate(month + '-01')).replace('.', ''))
}

export function dateShort(iso: string): string {
  return dayShort.format(parseLocalDate(iso)).replace('.', '').replace(' de ', ' ')
}

export function dateLong(iso: string): string {
  return capitalize(dayLong.format(parseLocalDate(iso)).replace('.', ''))
}

export function dateNumeric(iso: string): string {
  return dayNumeric.format(parseLocalDate(iso))
}

/** 'aaaa-mm-dd' -> 'dd/mm/aaaa' (vazio permanece vazio). */
export function formatDateInput(iso: string | null | undefined): string {
  if (!iso || !/^\d{4}-\d{2}-\d{2}$/.test(iso)) return ''
  const [y, m, d] = iso.split('-')
  return `${d}/${m}/${y}`
}

/** 'dd/mm/aaaa' -> 'aaaa-mm-dd', ou '' se incompleto ou se a data não existir (ex.: 31/02). */
export function parseDateInput(text: string): string {
  const m = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(text.trim())
  if (!m) return ''
  const [, dd, mm, yyyy] = m
  const d = Number(dd), mo = Number(mm), y = Number(yyyy)
  const date = new Date(y, mo - 1, d)
  if (y < 1900 || date.getFullYear() !== y || date.getMonth() !== mo - 1 || date.getDate() !== d) return ''
  return `${yyyy}-${mm}-${dd}`
}

export function dateTime(iso: string): string {
  return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(iso))
}

export type DueStatus = 'paid' | 'overdue' | 'today' | 'soon' | 'scheduled'

export function dueStatus(t: Pick<Transaction, 'paid' | 'dueDate'>): DueStatus {
  if (t.paid) return 'paid'
  const today = todayIso()
  if (t.dueDate < today) return 'overdue'
  if (t.dueDate === today) return 'today'
  const diff = (parseLocalDate(t.dueDate).getTime() - parseLocalDate(today).getTime()) / 86400000
  return diff <= 7 ? 'soon' : 'scheduled'
}

export function dueLabel(t: Pick<Transaction, 'paid' | 'dueDate' | 'type'>): string {
  switch (dueStatus(t)) {
    case 'paid':
      return t.type === 'INCOME' ? 'Recebido' : 'Pago'
    case 'overdue':
      return t.type === 'INCOME' ? 'Atrasado' : 'Vencido'
    case 'today':
      return 'Vence hoje'
    case 'soon':
      return 'Vence em breve'
    default:
      return 'Agendado'
  }
}

export const frequencyLabels: Record<Frequency, string> = {
  WEEKLY: 'Semanal',
  BIWEEKLY: 'Quinzenal',
  MONTHLY: 'Mensal',
  BIMONTHLY: 'Bimestral',
  QUARTERLY: 'Trimestral',
  SEMIANNUAL: 'Semestral',
  YEARLY: 'Anual',
}

export const accountTypeLabels: Record<AccountType, string> = {
  CHECKING: 'Conta corrente',
  SAVINGS: 'Poupança',
  CREDIT_CARD: 'Cartão de crédito',
  CASH: 'Dinheiro',
  INVESTMENT: 'Investimento',
  OTHER: 'Outra',
}

/** Converte texto digitado (ex.: "1.234,56") em número. */
export function parseMoneyInput(text: string): number | null {
  const digits = text.replace(/\D/g, '')
  if (!digits) return null
  return Number(digits) / 100
}

export const invoiceStatusLabels: Record<InvoiceStatus, string> = {
  OPEN: 'Aberta',
  CLOSED: 'Fechada',
  PARTIAL: 'Paga em parte',
  PAID: 'Paga',
}
