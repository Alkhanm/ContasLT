<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { refDebounced } from '@vueuse/core'
import { ListChecks, Search, X } from 'lucide-vue-next'
import { transactionsApi } from '@/api'
import type { Transaction, TransactionPage, TransactionType } from '@/api/types'
import AccountSelect from '@/components/app/AccountSelect.vue'
import EmptyState from '@/components/app/EmptyState.vue'
import Money from '@/components/app/Money.vue'
import TransactionRow from '@/components/app/TransactionRow.vue'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { dateLong } from '@/lib/format'
import { notifyError, notifySuccess } from '@/lib/notify'
import { cn } from '@/lib/utils'
import { useCatalogStore } from '@/stores/catalog'
import { usePeriodStore } from '@/stores/period'
import { useUiStore } from '@/stores/ui'

const period = usePeriodStore()
const ui = useUiStore()
const catalog = useCatalogStore()
const route = useRoute()
const router = useRouter()

type TypeFilter = 'ALL' | TransactionType
type StatusFilter = 'all' | 'pendente' | 'pago'

const typeFilter = ref<TypeFilter>((route.query.tipo as TypeFilter) || 'ALL')
const status = ref<StatusFilter>((route.query.status as StatusFilter) || 'all')
const accountId = ref<number | null>(route.query.conta ? Number(route.query.conta) : null)
const categoryId = ref<number | null>(route.query.categoria && route.query.categoria !== 'nenhuma' ? Number(route.query.categoria) : null)
const search = ref('')
const debouncedSearch = refDebounced(search, 300)

const page = ref<TransactionPage | null>(null)
const loading = ref(true)
const busyIds = ref(new Set<number>())

const typeOptions: { value: TypeFilter; label: string }[] = [
  { value: 'ALL', label: 'Tudo' },
  { value: 'EXPENSE', label: 'Despesas' },
  { value: 'INCOME', label: 'Receitas' },
]

async function load() {
  loading.value = page.value === null
  try {
    page.value = await transactionsApi.list({
      month: period.month,
      type: typeFilter.value === 'ALL' ? null : typeFilter.value,
      paid: status.value === 'all' ? null : status.value === 'pago',
      accountId: accountId.value,
      categoryId: categoryId.value,
      search: debouncedSearch.value,
    })
  } catch (err) {
    notifyError(err)
  } finally {
    loading.value = false
  }
}

watch([() => period.month, () => ui.dataVersion, typeFilter, status, accountId, categoryId, debouncedSearch], load, { immediate: true })

watch([typeFilter, status, accountId, categoryId], () => {
  router.replace({ query: {
    tipo: typeFilter.value === 'ALL' ? undefined : typeFilter.value,
    status: status.value === 'all' ? undefined : status.value,
    conta: accountId.value ?? undefined,
    categoria: categoryId.value ?? undefined,
  } })
})

const selectedCategory = computed(() => catalog.category(categoryId.value))

const groups = computed(() => {
  const map = new Map<string, Transaction[]>()
  for (const t of page.value?.items ?? []) {
    const list = map.get(t.dueDate) ?? []
    list.push(t)
    map.set(t.dueDate, list)
  }
  return [...map.entries()].map(([date, items]) => ({ date, items }))
})

async function togglePaid(tx: Transaction) {
  busyIds.value.add(tx.id)
  try {
    await transactionsApi.setPaid(tx.id, !tx.paid)
    notifySuccess(tx.paid ? 'Marcado como pendente' : tx.type === 'INCOME' ? 'Marcado como recebido' : 'Marcado como pago')
    await Promise.all([load(), catalog.reloadAccounts()])
  } catch (err) {
    notifyError(err)
  } finally {
    busyIds.value.delete(tx.id)
  }
}
</script>

<template>
  <div class="space-y-4">
    <div class="flex flex-col gap-2 md:flex-row md:items-center">
      <div role="radiogroup" class="grid grid-cols-3 gap-1 rounded-lg bg-muted p-1 md:w-72">
        <button
          v-for="o in typeOptions"
          :key="o.value"
          type="button"
          role="radio"
          :aria-checked="typeFilter === o.value"
          :class="cn('h-8 rounded-md text-xs font-semibold transition-colors', typeFilter === o.value ? 'bg-card shadow-xs' : 'text-muted-foreground hover:text-foreground')"
          @click="typeFilter = o.value"
        >
          {{ o.label }}
        </button>
      </div>
      <div class="flex flex-1 gap-2">
        <div class="relative flex-1">
          <Search class="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input v-model="search" placeholder="Buscar por descrição" class="pl-9" />
        </div>
        <Select v-model="status">
          <SelectTrigger class="w-32 shrink-0" aria-label="Situação"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="all">Todos</SelectItem>
            <SelectItem value="pendente">Pendentes</SelectItem>
            <SelectItem value="pago">Pagos</SelectItem>
          </SelectContent>
        </Select>
        <div class="hidden w-44 shrink-0 md:block">
          <AccountSelect v-model="accountId" allow-all />
        </div>
      </div>
    </div>

    <div v-if="selectedCategory" class="flex items-center gap-2 text-sm">
      <span class="text-muted-foreground">Categoria:</span>
      <button type="button" class="inline-flex items-center gap-1 rounded-full bg-accent px-3 py-1 text-xs font-semibold" @click="categoryId = null">
        {{ selectedCategory.name }} <X class="size-3" />
      </button>
    </div>

    <div v-if="page" class="flex items-center justify-between rounded-xl bg-card px-4 py-2.5 text-sm border">
      <span class="text-muted-foreground">{{ page.summary.count }} {{ page.summary.count === 1 ? 'lançamento' : 'lançamentos' }}</span>
      <span class="flex items-center gap-3">
        <Money :value="page.summary.income" type="INCOME" signed class="font-semibold" />
        <Money :value="page.summary.expense" type="EXPENSE" signed class="font-semibold" />
      </span>
    </div>

    <div v-if="loading" class="space-y-2">
      <Skeleton v-for="i in 6" :key="i" class="h-14 w-full rounded-xl" />
    </div>

    <div v-else-if="groups.length" class="space-y-4">
      <section v-for="g in groups" :key="g.date">
        <h2 class="mb-1 px-1 text-xs font-bold text-muted-foreground">{{ dateLong(g.date) }}</h2>
        <div class="divide-y rounded-2xl border bg-card px-4">
          <TransactionRow
            v-for="t in g.items" :key="t.id" :transaction="t"
            :busy="busyIds.has(t.id)" @open="ui.editTransaction" @toggle-paid="togglePaid"
          />
        </div>
      </section>
    </div>

    <div v-else class="rounded-2xl border border-dashed">
      <EmptyState :icon="ListChecks" title="Nenhum lançamento aqui" :description="search || typeFilter !== 'ALL' || status !== 'all' || categoryId ? 'Tente limpar os filtros.' : 'Toque em + para adicionar uma despesa ou receita.'" />
    </div>
  </div>
</template>
