import type { CategoryReport, CategorySpending, ItemPurchase, ItemReport, ItemSpending, MonthValue } from '@/api/types'
import type { CategoryRec, Id, ItemRec, TransactionRec } from '@/data/schema'
import { currentMonth, endOfMonth, firstDay, isMonth, monthOf, monthsBetween, plusMonthsYm, type YearMonth } from '@/domain/dates'
import { fromCents } from '@/domain/money'
import { normalize } from '@/domain/text'
import { isCreditCard } from './cards'
import { childrenOf, rootOf } from './catalog'
import { BusinessError, db, validate } from './context'
import { percent } from './dashboard'
import { ensureOccurrences } from './recurring'

/** Relatórios de gasto (porte de ReportService): por categoria (expansão do painel da tela inicial) e por item de comprovante. */

const HISTORY_MONTHS = 6
const DEFAULT_ITEM_MONTHS = 12
const UNCATEGORIZED_COLOR = '#9CA3AF'
const UNCATEGORIZED_ICON = 'circle-dashed'

/** Ordem de criação (o id sequencial do backend): createdAt e, no empate, o id. */
function byCreation(a: TransactionRec, b: TransactionRec) {
  return a.createdAt < b.createdAt ? -1 : a.createdAt > b.createdAt ? 1 : a.id < b.id ? -1 : a.id > b.id ? 1 : 0
}

function byDateThenCreation(a: TransactionRec, b: TransactionRec) {
  return a.dueDate < b.dueDate ? -1 : a.dueDate > b.dueDate ? 1 : byCreation(a, b)
}

const byTotalDesc = <T extends { total: number }>(a: T, b: T) => b.total - a.total

/**
 * Despesas do mês por categoria, como o painel da tela inicial (todas as contas menos cartões; pagas e pendentes),
 * mais as subcategorias, o mês anterior e os últimos {@link HISTORY_MONTHS} meses de cada uma.
 */
export function categoryReport(month: YearMonth): CategoryReport {
  const start = plusMonthsYm(month, -(HISTORY_MONTHS - 1))
  const history = monthsBetween(start, month)
  for (const ym of history) ensureOccurrences(ym)
  const s = db()
  const from = firstDay(start)
  const to = endOfMonth(month)
  // Como findByUserAndPeriod: fora de cartão, da data mais recente para a mais antiga.
  const all = s.list('transaction')
    .filter((t) => t.dueDate >= from && t.dueDate <= to && !isCreditCard(s.get('account', t.accountId)))
    .sort((a, b) => byDateThenCreation(b, a))

  // A chave nula é "Sem categoria".
  const roots = new Map<Id | null, CategoryRec>()
  const subs = new Map<Id, CategoryRec>()
  // Totais por raiz e mês (centavos).
  const rootByMonth = new Map<Id | null, Map<YearMonth, number>>()
  const rootCount = new Map<Id | null, number>()
  // Totais do mês por subcategoria (ou raiz, para o que foi lançado direto nela), agrupados pela raiz.
  const childTotals = new Map<Id | null, Map<Id, number>>()
  const childCounts = new Map<Id | null, Map<Id, number>>()
  let total = 0
  let previousTotal = 0
  let count = 0
  const previous = plusMonthsYm(month, -1)

  for (const t of all) {
    if (t.transferId || t.type !== 'EXPENSE') continue
    const ym = monthOf(t.dueDate)
    const c = s.get('category', t.categoryId)
    const root = c ? rootOf(c) : null
    const rootId = root?.id ?? null
    if (root && !roots.has(rootId)) roots.set(rootId, root)
    let byMonth = rootByMonth.get(rootId)
    if (!byMonth) rootByMonth.set(rootId, (byMonth = new Map()))
    byMonth.set(ym, (byMonth.get(ym) ?? 0) + t.amount)
    if (ym === previous) previousTotal += t.amount
    if (ym !== month) continue
    total += t.amount
    count++
    rootCount.set(rootId, (rootCount.get(rootId) ?? 0) + 1)
    if (c) {
      if (c.parentId != null && !subs.has(c.id)) subs.set(c.id, c)
      let ct = childTotals.get(rootId)
      if (!ct) childTotals.set(rootId, (ct = new Map()))
      ct.set(c.id, (ct.get(c.id) ?? 0) + t.amount)
      let cc = childCounts.get(rootId)
      if (!cc) childCounts.set(rootId, (cc = new Map()))
      cc.set(c.id, (cc.get(c.id) ?? 0) + 1)
    }
  }

  const items: CategorySpending[] = []
  for (const [rootId, byMonth] of rootByMonth) {
    const monthTotal = byMonth.get(month) ?? 0
    if (monthTotal === 0) continue
    const root = roots.get(rootId) ?? null
    const values: MonthValue[] = history.map((ym) => ({ month: ym, total: fromCents(byMonth.get(ym) ?? 0) }))
    const children: CategorySpending[] = []
    const ct = childTotals.get(rootId) ?? new Map<Id, number>()
    const hasSubs = [...ct.keys()].some((id) => subs.has(id))
    if (hasSubs && root) {
      for (const [id, value] of ct) {
        const sub = subs.get(id)
        const n = childCounts.get(rootId)!.get(id)!
        const base = sub
          ? { categoryId: sub.id, name: sub.name, color: sub.color, icon: sub.icon }
          : { categoryId: rootId, name: 'Geral', color: root.color, icon: root.icon }
        children.push({
          ...base, total: fromCents(value), percent: percent(value, monthTotal), count: n, previousTotal: null,
          children: [], history: [],
        })
      }
      children.sort(byTotalDesc)
    }
    items.push({
      categoryId: rootId,
      name: root?.name ?? 'Sem categoria',
      color: root?.color ?? UNCATEGORIZED_COLOR,
      icon: root?.icon ?? UNCATEGORIZED_ICON,
      total: fromCents(monthTotal),
      percent: percent(monthTotal, total),
      count: rootCount.get(rootId) ?? 0,
      previousTotal: fromCents(byMonth.get(previous) ?? 0),
      children,
      history: values,
    })
  }
  items.sort(byTotalDesc)
  return { month, total: fromCents(total), previousTotal: fromCents(previousTotal), count, items }
}

/**
 * Itens de comprovante entre dois meses (inclusive), agrupados pelo nome normalizado. Inclui compras no cartão: aqui
 * o que importa é o produto, não o caixa. `categoryId` filtra pela categoria do lançamento (uma categoria de primeiro
 * nível inclui as subcategorias); `search` filtra pelo nome do item. Sem `from`/`to`: os últimos 12 meses até o mês
 * atual.
 */
export function itemReport(filter: { from?: string; to?: string; categoryId?: Id | null; search?: string }): ItemReport {
  validate([
    ['from', !filter.from?.trim() || isMonth(filter.from), 'Mês inválido'],
    ['to', !filter.to?.trim() || isMonth(filter.to), 'Mês inválido'],
  ])
  const end = filter.to?.trim() ? filter.to : currentMonth()
  const start = filter.from?.trim() ? filter.from : plusMonthsYm(end, -(DEFAULT_ITEM_MONTHS - 1))
  if (start > end) throw new BusinessError('O mês inicial não pode ser depois do final')

  const s = db()
  let categoryIds: Set<Id> | null = null
  if (filter.categoryId != null) {
    categoryIds = new Set([filter.categoryId, ...childrenOf(filter.categoryId).map((c) => c.id)])
  }
  const needle = normalize(filter.search)
  const from = firstDay(start)
  const to = endOfMonth(end)

  interface Line { item: ItemRec; tx: TransactionRec }
  const groups = new Map<string, Line[]>()
  // Como TransactionItemRepository.findByUserAndPeriod: em ordem de compra (data, lançamento, posição do item).
  const txs = s.list('transaction').filter((t) => t.items.length && t.dueDate >= from && t.dueDate <= to).sort(byDateThenCreation)
  for (const t of txs) {
    if (t.transferId || t.type !== 'EXPENSE') continue
    if (categoryIds && (t.categoryId == null || !categoryIds.has(t.categoryId) || !s.get('category', t.categoryId))) continue
    for (const item of t.items) {
      const key = itemKey(item.description)
      if (needle && !key.includes(needle)) continue
      let list = groups.get(key)
      if (!list) groups.set(key, (list = []))
      list.push({ item, tx: t })
    }
  }

  const items: ItemSpending[] = []
  let total = 0
  let count = 0
  for (const [key, list] of groups) {
    const purchases: ItemPurchase[] = []
    const byMonth = new Map<YearMonth, number>()
    let itemTotal = 0
    let quantity = 0
    let first: number | null = null
    let last: number | null = null
    let min: number | null = null
    let max: number | null = null
    for (const { item, tx } of list) {
      const unit = unitPrice(item)
      purchases.push({
        transactionId: tx.id, date: tx.dueDate, merchant: tx.merchant, description: item.description,
        quantity: item.quantity, unitPrice: fromCents(unit), total: fromCents(item.total),
      })
      const ym = monthOf(tx.dueDate)
      byMonth.set(ym, (byMonth.get(ym) ?? 0) + item.total)
      itemTotal += item.total
      quantity += item.quantity ?? 1
      first = first ?? unit
      last = unit
      min = min == null || unit < min ? unit : min
      max = max == null || unit > max ? unit : max
    }
    const latest = list[list.length - 1]
    const c = s.get('category', latest.tx.categoryId)
    const months: MonthValue[] = [...byMonth.keys()].sort().map((m) => ({ month: m, total: fromCents(byMonth.get(m)!) }))
    items.push({
      key,
      description: latest.item.description.trim(),
      categoryId: c?.id ?? null,
      categoryName: c ? categoryPath(c) : null,
      color: c?.color ?? UNCATEGORIZED_COLOR,
      icon: c?.icon ?? UNCATEGORIZED_ICON,
      count: list.length,
      quantity: round6(quantity),
      total: fromCents(itemTotal),
      firstUnitPrice: fromCents(first),
      lastUnitPrice: fromCents(last),
      minUnitPrice: fromCents(min),
      maxUnitPrice: fromCents(max),
      months,
      purchases,
    })
    total += itemTotal
    count += list.length
  }
  items.sort(byTotalDesc)
  return { from: start, to: end, total: fromCents(total), count, items }
}

/** Chave de agrupamento: o nome sem acentos, dígitos e pontuação; se não sobrar nada, o nome em minúsculas. */
export function itemKey(description: string) {
  const key = normalize(description)
  return key === '' ? description.trim().toLowerCase() : key
}

/** Preço unitário informado, ou total ÷ quantidade (centavos, arredondado); sem quantidade, o total (uma unidade). */
export function unitPrice(i: ItemRec): number {
  if (i.unitPrice != null && i.unitPrice > 0) return i.unitPrice
  if (i.quantity != null && i.quantity > 0) return Math.round(i.total / i.quantity + 1e-9)
  return i.total
}

/** Soma de quantidades fracionárias sem o ruído do ponto flutuante (o backend somava BigDecimal). */
const round6 = (n: number) => Math.round(n * 1e6) / 1e6

function categoryPath(c: CategoryRec) {
  const parent = c.parentId ? db().get('category', c.parentId) : null
  return parent ? `${parent.name} › ${c.name}` : c.name
}
