<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { refDebounced } from '@vueuse/core'
import { ArrowLeftRight, ListChecks, Search } from 'lucide-vue-next'
import { transactionsApi, transfersApi } from '@/api'
import type { Transaction, TransactionPage, TransactionType } from '@/api/types'
import AccountSelect from '@/components/app/AccountSelect.vue'
import CategorySelect from '@/components/app/CategorySelect.vue'
import EmptyState from '@/components/app/EmptyState.vue'
import Money from '@/components/app/Money.vue'
import TransactionRow from '@/components/app/TransactionRow.vue'
import TransferMatchSheet from '@/components/app/TransferMatchSheet.vue'
import { Button } from '@/components/ui/button'
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

type TypeFilter = 'ALL' | TransactionType | 'TRANSFER'
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
const suggestionCount = ref(0)
const matchOpen = ref(route.query.cruzar === '1')

const typeOptions: { value: TypeFilter; label: string }[] = [
  { value: 'ALL', label: 'Tudo' },
  { value: 'EXPENSE', label: 'Despesas' },
  { value: 'INCOME', label: 'Receitas' },
  { value: 'TRANSFER', label: 'Transfer.' },
]

async function load() {
  loading.value = page.value === null
  try {
    page.value = await transactionsApi.list({
      month: period.month,
      type: typeFilter.value === 'ALL' || typeFilter.value === 'TRANSFER' ? null : typeFilter.value,
      transfers: typeFilter.value === 'TRANSFER' ? true : null,
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

async function loadSuggestionCount() {
  try {
    suggestionCount.value = (await transfersApi.suggestions()).items.length
  } catch {
    suggestionCount.value = 0
  }
}
watch(() => ui.dataVersion, loadSuggestionCount, { immediate: true })

function onLinked() {
  ui.dataChanged()
  catalog.reloadAccounts()
}

watch(matchOpen, (open) => {
  if (!open && route.query.cruzar) router.replace({ query: { ...route.query, cruzar: undefined } })
})

watch([typeFilter, status, accountId, categoryId], () => {
  router.replace({ query: {
    tipo: typeFilter.value === 'ALL' ? undefined : typeFilter.value,
    status: status.value === 'all' ? undefined : status.value,
    conta: accountId.value ?? undefined,
    categoria: categoryId.value ?? undefined,
  } })
})

/** Tipo das categorias oferecidas no filtro; sem tipo (Todos/Transferências) mostra despesas e receitas agrupadas. */
const categoryType = computed(() => (typeFilter.value === 'EXPENSE' || typeFilter.value === 'INCOME' ? typeFilter.value : undefined))
watch(categoryType, (type) => {
  const c = catalog.category(categoryId.value)
  if (type && c && c.type !== type) categoryId.value = null
})

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
      <div role="radiogroup" class="grid grid-cols-4 gap-1 rounded-lg bg-muted p-1 md:w-96">
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

    <div class="md:w-96">
      <CategorySelect v-model="categoryId" :type="categoryType" none-label="Todas as categorias" child-all-label="Todas" />
    </div>

    <div v-if="suggestionCount" class="flex items-center gap-3 rounded-xl border border-primary/30 bg-primary/5 px-4 py-2.5 text-sm">
      <ArrowLeftRight class="size-4 shrink-0 text-primary" />
      <span class="flex-1">
        <span class="font-semibold">{{ suggestionCount }} {{ suggestionCount === 1 ? 'possível transferência' : 'possíveis transferências' }}</span>
        <span class="text-muted-foreground"> entre suas contas {{ suggestionCount === 1 ? 'está contando' : 'estão contando' }} como despesa e receita.</span>
      </span>
      <Button size="sm" @click="matchOpen = true">Revisar</Button>
    </div>

    <div v-if="page" class="flex items-center justify-between gap-3 rounded-xl bg-card px-4 py-2.5 text-sm border">
      <span class="text-muted-foreground">{{ page.summary.count }} {{ page.summary.count === 1 ? 'lançamento' : 'lançamentos' }}</span>
      <span class="flex items-center gap-3">
        <span v-if="page.summary.transferOut > 0 || page.summary.transferIn > 0" class="inline-flex items-center gap-1 text-muted-foreground" title="Transferências entre contas, fora do resultado">
          <ArrowLeftRight class="size-3.5" />
          <Money :value="Math.max(page.summary.transferOut, page.summary.transferIn)" muted class="font-semibold" />
        </span>
        <template v-if="typeFilter !== 'TRANSFER'">
          <Money :value="page.summary.income" type="INCOME" signed class="font-semibold" />
          <Money :value="page.summary.expense" type="EXPENSE" signed class="font-semibold" />
        </template>
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
      <EmptyState
        :icon="typeFilter === 'TRANSFER' ? ArrowLeftRight : ListChecks"
        :title="typeFilter === 'TRANSFER' ? 'Nenhuma transferência neste mês' : 'Nenhum lançamento aqui'"
        :description="typeFilter === 'TRANSFER' ? 'Transferências entre suas contas aparecem aqui e não contam como despesa nem receita.' : search || typeFilter !== 'ALL' || status !== 'all' || categoryId ? 'Tente limpar os filtros.' : 'Toque em + para adicionar uma despesa ou receita.'"
      >
        <Button v-if="typeFilter === 'TRANSFER'" variant="outline" size="sm" @click="matchOpen = true">Procurar pares entre contas</Button>
      </EmptyState>
    </div>

    <TransferMatchSheet v-model:open="matchOpen" @linked="onLinked" />
  </div>
</template>
