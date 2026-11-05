import type { CategoryTotal, Dashboard, MonthTotal } from '@/api/types'
import type { CategoryRec, TransactionRec } from '@/data/schema'
import { currentMonth, endOfMonth, firstDay, monthOf, monthsBetween, plusDays, plusMonthsYm, today, type YearMonth } from '@/domain/dates'
import { fromCents } from '@/domain/money'
import { isCreditCard } from './cards'
import { listAccounts, rootOf } from './catalog'
import { db } from './context'
import { transactionDto } from './mappers'
import { ensureOccurrences } from './recurring'

/** Painel do mês (porte de DashboardService). */

const HISTORY_MONTHS = 6

/** Percentual com uma casa, arredondado como o HALF_UP do backend. */
export function percent(part: number, total: number) {
  if (total === 0) return 0
  return Math.round((part * 1000) / total) / 10
}

export function buildDashboard(month: YearMonth): Dashboard {
  const now = today()
  const historyStart = plusMonthsYm(month, -(HISTORY_MONTHS - 1))
  for (const ym of monthsBetween(historyStart, month)) ensureOccurrences(ym)
  // Também o mês atual, para que vencimentos próximos apareçam mesmo vendo meses passados.
  ensureOccurrences(currentMonth())

  const s = db()
  const all = s.list('transaction')
  const cash = all.filter((t) => !isCreditCard(s.get('account', t.accountId)))
  const from = firstDay(month)
  const to = endOfMonth(month)
  const historyItems = cash.filter((t) => t.dueDate >= firstDay(historyStart) && t.dueDate <= to)
  const items = historyItems.filter((t) => t.dueDate >= from)

  let income = 0, expense = 0, paidIncome = 0, paidExpense = 0, transfers = 0
  const roots = new Map<string, CategoryRec>()
  const byCategory = new Map<string, TransactionRec[]>()
  const uncategorized: TransactionRec[] = []
  for (const t of items) {
    if (t.transferId) {
      // Movimentação interna: não é receita nem despesa. Conta-se só a perna de saída.
      if (t.type === 'EXPENSE') transfers += t.amount
      continue
    }
    if (t.type === 'INCOME') {
      income += t.amount
      if (t.paid) paidIncome += t.amount
    } else {
      expense += t.amount
      if (t.paid) paidExpense += t.amount
      const c = s.get('category', t.categoryId)
      if (!c) uncategorized.push(t)
      else {
        // Subcategorias somam na categoria pai; o clique no painel filtra pai + filhas.
        const root = rootOf(c)
        if (!roots.has(root.id)) roots.set(root.id, root)
        const list = byCategory.get(root.id) ?? []
        list.push(t)
        byCategory.set(root.id, list)
      }
    }
  }

  const sum = (list: TransactionRec[]) => list.reduce((acc, t) => acc + t.amount, 0)
  const totals: (CategoryTotal & { cents: number })[] = []
  for (const [id, list] of byCategory) {
    const c = roots.get(id)!
    const total = sum(list)
    totals.push({ categoryId: c.id, name: c.name, color: c.color, icon: c.icon, total: fromCents(total), cents: total, percent: percent(total, expense), count: list.length })
  }
  if (uncategorized.length) {
    const total = sum(uncategorized)
    totals.push({ categoryId: null, name: 'Sem categoria', color: '#9CA3AF', icon: 'circle-dashed', total: fromCents(total), cents: total, percent: percent(total, expense), count: uncategorized.length })
  }
  totals.sort((a, b) => b.cents - a.cents)

  const accounts = listAccounts(false)
  // Cartão de crédito não é caixa: o saldo total soma só as contas de dinheiro.
  const currentBalance = accounts.filter((a) => a.type !== 'CREDIT_CARD').reduce((acc, a) => acc + Math.round(a.balance * 100), 0)

  const history: MonthTotal[] = monthsBetween(historyStart, month).map((ym) => {
    let inc = 0, exp = 0
    for (const t of historyItems) {
      if (t.transferId || monthOf(t.dueDate) !== ym) continue
      if (t.type === 'INCOME') inc += t.amount
      else exp += t.amount
    }
    return { month: ym, income: fromCents(inc), expense: fromCents(exp) }
  })

  const byDueAsc = (a: TransactionRec, b: TransactionRec) => (a.dueDate < b.dueDate ? -1 : a.dueDate > b.dueDate ? 1 : 0)
  const week = plusDays(now, 7)
  const overdue = cash.filter((t) => !t.paid && t.dueDate < now).sort(byDueAsc)
  const upcoming = cash.filter((t) => !t.paid && t.dueDate >= now && t.dueDate <= week).sort(byDueAsc)

  return {
    month,
    income: fromCents(income),
    expense: fromCents(expense),
    balance: fromCents(income - expense),
    paidIncome: fromCents(paidIncome),
    paidExpense: fromCents(paidExpense),
    pendingIncome: fromCents(income - paidIncome),
    pendingExpense: fromCents(expense - paidExpense),
    transfers: fromCents(transfers),
    currentBalance: fromCents(currentBalance),
    accounts,
    byCategory: totals.map(({ cents: _c, ...rest }) => rest),
    overdue: overdue.map((t) => transactionDto(t)),
    upcoming: upcoming.map((t) => transactionDto(t)),
    history,
  }
}
