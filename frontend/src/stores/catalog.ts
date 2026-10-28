import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { accountsApi, categoriesApi } from '@/api'
import type { Account, Category } from '@/api/types'

const byName = (a: Category, b: Category) => a.name.localeCompare(b.name, 'pt-BR')

/** Contas e categorias do usuário, carregadas uma vez e reutilizadas em formulários e filtros. */
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
      const [a, c] = await Promise.all([accountsApi.list(true), categoriesApi.list(true)])
      accounts.value = a
      categories.value = c
      loaded.value = true
    } finally {
      loading.value = false
    }
  }

  async function reloadAccounts() {
    accounts.value = await accountsApi.list(true)
  }

  async function reloadCategories() {
    categories.value = await categoriesApi.list(true)
  }

  function account(id: number | null | undefined) {
    return accounts.value.find((a) => a.id === id) ?? null
  }

  function category(id: number | null | undefined) {
    return categories.value.find((c) => c.id === id) ?? null
  }

  function childrenOf(parentId: number | null | undefined, includeArchived = false) {
    if (parentId == null) return []
    return categories.value.filter((c) => c.parentId === parentId && (includeArchived || !c.archived)).sort(byName)
  }

  /** "Pai › Filha" para subcategoria, ou só o nome. */
  function categoryPath(id: number | null | undefined) {
    const c = category(id)
    if (!c) return null
    const parent = c.parentId != null ? category(c.parentId) : null
    return parent ? `${parent.name} › ${c.name}` : c.name
  }

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
