import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, type MaybeRefOrGetter, toValue } from 'vue'
import { accountsApi, categoriesApi, dashboardApi, importsApi, recurringApi, reportsApi, rulesApi, transactionsApi, transfersApi } from '@/api'
import type { TransactionFilter } from '@/api/types'
import { queryClient } from '@/lib/queryClient'

/** Chaves do cache. Toda leitura da API passa por aqui, para a invalidação acertar sempre a mesma chave. */
export const keys = {
  accounts: ['accounts'] as const,
  categories: ['categories'] as const,
  dashboard: (month: string) => ['dashboard', month] as const,
  transactions: (filter: TransactionFilter) => ['transactions', normalizeFilter(filter)] as const,
  suggestions: ['transfers', 'suggestions'] as const,
  categoryReport: (month: string) => ['reports', 'categories', month] as const,
  itemReport: (p: ItemReportParams) => ['reports', 'items', p] as const,
  recurring: ['recurring'] as const,
  imports: ['imports'] as const,
  rules: ['rules'] as const,
}

export type ItemReportParams = { from: string; to: string; categoryId: number | null }

/** Mesmo filtro, mesma chave: campos ausentes viram null/false/'' antes de entrar na chave. */
function normalizeFilter(f: TransactionFilter): Required<TransactionFilter> {
  return {
    month: f.month,
    accountId: f.accountId ?? null,
    categoryId: f.categoryId ?? null,
    uncategorized: f.uncategorized ?? false,
    type: f.type ?? null,
    paid: f.paid ?? null,
    search: f.search ?? '',
    transfers: f.transfers ?? null,
    invoice: f.invoice ?? false,
  }
}

/** Filtro da lista de lançamentos como ela abre (tudo do mês). Usado para pré-carregar a partir do início. */
export function defaultTransactionFilter(month: string): TransactionFilter {
  return normalizeFilter({ month })
}

export function useDashboard(month: MaybeRefOrGetter<string>) {
  return useQuery({
    queryKey: computed(() => keys.dashboard(toValue(month))),
    queryFn: () => dashboardApi.get(toValue(month)),
  })
}

/** Lista do mês; ao mudar o filtro, a lista anterior fica na tela até a nova chegar (como antes). */
export function useTransactions(filter: MaybeRefOrGetter<TransactionFilter>) {
  return useQuery({
    queryKey: computed(() => keys.transactions(toValue(filter))),
    queryFn: () => transactionsApi.list(toValue(filter)),
    placeholderData: keepPreviousData,
  })
}

export function useTransferSuggestions(enabled: MaybeRefOrGetter<boolean> = true) {
  return useQuery({
    queryKey: keys.suggestions,
    queryFn: () => transfersApi.suggestions(),
    enabled: computed(() => toValue(enabled)),
  })
}

export function useCategoryReport(month: MaybeRefOrGetter<string>) {
  return useQuery({
    queryKey: computed(() => keys.categoryReport(toValue(month))),
    queryFn: () => reportsApi.categories(toValue(month)),
  })
}

export function useItemReport(params: MaybeRefOrGetter<ItemReportParams>) {
  return useQuery({
    queryKey: computed(() => keys.itemReport(toValue(params))),
    queryFn: () => reportsApi.items(toValue(params)),
    placeholderData: keepPreviousData,
  })
}

export function useRecurringBills() {
  return useQuery({ queryKey: keys.recurring, queryFn: () => recurringApi.list() })
}

export function useImportHistory() {
  return useQuery({ queryKey: keys.imports, queryFn: () => importsApi.history() })
}

export function useRules() {
  return useQuery({ queryKey: keys.rules, queryFn: () => rulesApi.list() })
}

// ---------- Fora de componentes (stores e formulários) ----------

export function fetchAccounts(fresh = false) {
  return queryClient.fetchQuery({ queryKey: keys.accounts, queryFn: () => accountsApi.list(true), staleTime: fresh ? 0 : 5 * 60_000 })
}

export function fetchCategories(fresh = false) {
  return queryClient.fetchQuery({ queryKey: keys.categories, queryFn: () => categoriesApi.list(true), staleTime: fresh ? 0 : 5 * 60_000 })
}

export function fetchTransactions(filter: TransactionFilter) {
  return queryClient.fetchQuery({ queryKey: keys.transactions(filter), queryFn: () => transactionsApi.list(filter) })
}

/** Aquece o cache sem bloquear nada (ex.: a lista do mês enquanto o usuário ainda está no início). */
export function prefetchTransactions(filter: TransactionFilter) {
  void queryClient.prefetchQuery({ queryKey: keys.transactions(filter), queryFn: () => transactionsApi.list(filter) })
}

/**
 * Depois de qualquer gravação: marca como velho tudo o que deriva de lançamentos. O que está na tela refaz a
 * consulta em segundo plano; o resto só quando for aberto. Contas e categorias são recarregadas pela store.
 */
export function invalidateData() {
  for (const key of [['dashboard'], ['transactions'], ['transfers'], ['reports'], ['recurring'], ['imports'], ['rules']]) {
    void queryClient.invalidateQueries({ queryKey: key })
  }
}
