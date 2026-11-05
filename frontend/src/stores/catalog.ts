import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { Account, Category, Id } from '@/api/types'
import { onDataChange } from '@/data/session'
import { fetchAccounts, fetchCategories } from '@/composables/queries'

const byName = (a: Category, b: Category) => a.name.localeCompare(b.name, 'pt-BR')

/**
 * Contas e categorias do usuário, reutilizadas em formulários e filtros. Calculadas dos dados do aparelho e
 * recalculadas sozinhas quando qualquer dado muda (saldos mudam com quase toda gravação).
 */
export const useCatalogStore = defineStore('catalog', () => {
  const accounts = ref<Account[]>([])
  const categories = ref<Category[]>([])
  const loaded = ref(false)
  const loading = ref(false)

  const activeAccounts = computed(() => accounts.value.filter((a) => !a.archived))
  const activeCategories = computed(() => categories.value.filter((c) => !c.archived))
  const expenseCategories = computed(() => activeCategories.value.filter((c) => c.type === 'EXPENSE'))
  const incomeCategories = computed(() => activeCategories.value.filter((c) => c.type === 'INCOME'))
  /** Categorias de primeiro nível (sem pai), ativas, em ordem alfabética. */
  const rootCategories = computed(() => activeCategories.value.filter((c) => c.parentId == null).sort(byName))

  async function load(force = false) {
    if (loaded.value && !force) return
    if (loading.value) return
    loading.value = true
    try {
      const [a, c] = await Promise.all([fetchAccounts(force), fetchCategories(force)])
      accounts.value = a
      categories.value = c
      loaded.value = true
    } finally {
      loading.value = false
    }
  }

  async function reloadAccounts() {
    accounts.value = await fetchAccounts(true)
  }

  async function reloadCategories() {
    categories.value = await fetchCategories(true)
  }

  function account(id: Id | null | undefined) {
    return accounts.value.find((a) => a.id === id) ?? null
  }

  function category(id: Id | null | undefined) {
    return categories.value.find((c) => c.id === id) ?? null
  }

  function childrenOf(parentId: Id | null | undefined, includeArchived = false) {
    if (parentId == null) return []
    return categories.value.filter((c) => c.parentId === parentId && (includeArchived || !c.archived)).sort(byName)
  }

  /** "Pai › Filha" para subcategoria, ou só o nome. */
  function categoryPath(id: Id | null | undefined) {
    const c = category(id)
    if (!c) return null
    const parent = c.parentId != null ? category(c.parentId) : null
    return parent ? `${parent.name} › ${c.name}` : c.name
  }

  onDataChange(() => {
    if (loaded.value) void load(true)
  })

  function reset() {
    accounts.value = []
    categories.value = []
    loaded.value = false
  }

  return {
    accounts, categories, loaded, loading, activeAccounts, activeCategories, expenseCategories, incomeCategories,
    rootCategories, load, reloadAccounts, reloadCategories, account, category, childrenOf, categoryPath, reset,
  }
})
