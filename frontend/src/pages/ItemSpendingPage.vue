<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ChevronDown, Minus, Search, ShoppingBasket, TrendingDown, TrendingUp } from 'lucide-vue-next'
import { useItemReport } from '@/composables/queries'
import type { ItemSpending } from '@/api/types'
import CategoryIcon from '@/components/app/CategoryIcon.vue'
import CategorySelect from '@/components/app/CategorySelect.vue'
import EmptyState from '@/components/app/EmptyState.vue'
import Money from '@/components/app/Money.vue'
import MonthBars from '@/components/app/MonthBars.vue'
import PriceLine from '@/components/app/PriceLine.vue'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { currentMonth, dateShort, money, moneyPlain, shiftMonth } from '@/lib/format'
import { notifyError } from '@/lib/notify'
import { cn } from '@/lib/utils'


type RangeKey = '3' | '6' | '12' | '24' | 'all'
type SortKey = 'total' | 'count' | 'up' | 'down' | 'name'

const range = ref<RangeKey>('12')
const categoryId = ref<string | null>(null)
const search = ref('')
const sort = ref<SortKey>('total')

const expanded = ref(new Set<string>())

const rangeOptions: { value: RangeKey; label: string }[] = [
  { value: '3', label: 'Últimos 3 meses' },
  { value: '6', label: 'Últimos 6 meses' },
  { value: '12', label: 'Últimos 12 meses' },
  { value: '24', label: 'Últimos 24 meses' },
  { value: 'all', label: 'Desde o início' },
]
const sortOptions: { value: SortKey; label: string }[] = [
  { value: 'total', label: 'Maior gasto' },
  { value: 'count', label: 'Mais comprados' },
  { value: 'up', label: 'Maior alta de preço' },
  { value: 'down', label: 'Maior queda de preço' },
  { value: 'name', label: 'Nome' },
]

const params = computed(() => {
  const to = currentMonth()
  const from = range.value === 'all' ? '2000-01' : shiftMonth(to, -(Number(range.value) - 1))
  return { from, to, categoryId: categoryId.value }
})
const { data, isPending: loading, error } = useItemReport(params)
watch(error, (err) => { if (err) notifyError(err) })

/** Variação do preço unitário entre a primeira e a última compra, em %, ou null sem base. */
function priceChange(item: ItemSpending) {
  if (item.firstUnitPrice == null || item.lastUnitPrice == null || item.firstUnitPrice <= 0 || item.count < 2) return null
  return ((item.lastUnitPrice - item.firstUnitPrice) / item.firstUnitPrice) * 100
}

function normalize(s: string) {
  return s.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase()
}

const rows = computed(() => {
  const needle = normalize(search.value.trim())
  const list = (data.value?.items ?? [])
    .filter((item) => !needle || normalize(item.description).includes(needle) || item.key.includes(needle))
    .map((item) => ({ item, change: priceChange(item) }))
  const by: Record<SortKey, (a: typeof list[number], b: typeof list[number]) => number> = {
    total: (a, b) => b.item.total - a.item.total,
    count: (a, b) => b.item.count - a.item.count || b.item.total - a.item.total,
    up: (a, b) => (b.change ?? -Infinity) - (a.change ?? -Infinity),
    down: (a, b) => (a.change ?? Infinity) - (b.change ?? Infinity),
    name: (a, b) => a.item.description.localeCompare(b.item.description, 'pt-BR'),
  }
  return [...list].sort(by[sort.value])
})

const shownTotal = computed(() => rows.value.reduce((sum, r) => sum + r.item.total, 0))
const shownCount = computed(() => rows.value.reduce((sum, r) => sum + r.item.count, 0))

function toggle(key: string) {
  if (expanded.value.has(key)) expanded.value.delete(key)
  else expanded.value.add(key)
}

function quantityLabel(q: number | null) {
  if (q == null) return ''
  return Number.isInteger(q) ? String(q) : q.toLocaleString('pt-BR', { maximumFractionDigits: 3 })
}
</script>

<template>
  <div class="space-y-4">
    <div class="flex flex-col gap-2 md:flex-row md:items-center">
      <div class="relative flex-1">
        <Search class="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input v-model="search" placeholder="Buscar item" class="pl-9" aria-label="Buscar item" />
      </div>
      <div class="grid grid-cols-2 gap-2 md:flex">
        <Select v-model="range">
          <SelectTrigger class="md:w-44" aria-label="Período"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem v-for="o in rangeOptions" :key="o.value" :value="o.value">{{ o.label }}</SelectItem>
          </SelectContent>
        </Select>
        <Select v-model="sort">
          <SelectTrigger class="md:w-48" aria-label="Ordenar por"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem v-for="o in sortOptions" :key="o.value" :value="o.value">{{ o.label }}</SelectItem>
          </SelectContent>
        </Select>
        <div class="col-span-2 md:w-56">
          <CategorySelect v-model="categoryId" type="EXPENSE" none-label="Todas as categorias" />
        </div>
      </div>
    </div>

    <div v-if="loading && !data" class="space-y-2">
      <Skeleton v-for="i in 6" :key="i" class="h-16 w-full rounded-xl" />
    </div>

    <template v-else-if="data">
      <div class="flex items-center justify-between gap-3 rounded-xl border bg-card px-4 py-2.5 text-sm">
        <span class="text-muted-foreground">
          {{ rows.length }} {{ rows.length === 1 ? 'item' : 'itens' }} · {{ shownCount }} {{ shownCount === 1 ? 'compra' : 'compras' }}
        </span>
        <Money :value="shownTotal" type="EXPENSE" signed class="font-bold" />
      </div>

      <div v-if="rows.length" class="rounded-2xl border bg-card">
        <ul class="divide-y">
          <li v-for="{ item, change } in rows" :key="item.key">
            <button
              type="button" class="flex w-full items-center gap-3 px-4 py-3 text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              :aria-expanded="expanded.has(item.key)" @click="toggle(item.key)"
            >
              <CategoryIcon :icon="item.icon" :color="item.color" :title="item.categoryName ?? 'Sem categoria'" />
              <span class="min-w-0 flex-1">
                <span class="flex items-baseline justify-between gap-3">
                  <span class="truncate text-sm font-semibold">{{ item.description }}</span>
                  <Money :value="item.total" class="text-sm font-bold" />
                </span>
                <span class="mt-0.5 flex items-center justify-between gap-3 text-[11px] text-muted-foreground">
                  <span class="truncate">
                    {{ item.categoryName ?? 'Sem categoria' }} · {{ item.count }} {{ item.count === 1 ? 'compra' : 'compras' }}
                    <template v-if="item.months.length > 1"> em {{ item.months.length }} meses</template>
                  </span>
                  <span v-if="item.lastUnitPrice != null" class="flex shrink-0 items-center gap-1.5 tabular">
                    <span>{{ money(item.lastUnitPrice) }}/un</span>
                    <span v-if="change !== null" :class="cn('inline-flex items-center gap-0.5 font-semibold', change > 0 ? 'text-expense' : change < 0 ? 'text-income' : '')" :title="`Da primeira compra (${money(item.firstUnitPrice)}) para a última`">
                      <component :is="change > 0 ? TrendingUp : change < 0 ? TrendingDown : Minus" class="size-3" />
                      {{ change > 0 ? '+' : '' }}{{ change.toFixed(0) }}%
                    </span>
                  </span>
                </span>
              </span>
              <PriceLine v-if="item.count > 1" :purchases="item.purchases" :color="item.color" class="hidden shrink-0 sm:block" />
              <ChevronDown :class="cn('size-4 shrink-0 text-muted-foreground transition-transform', expanded.has(item.key) && 'rotate-180')" />
            </button>

            <div v-if="expanded.has(item.key)" class="border-t bg-muted/30 px-4 pb-3 pt-2">
              <div class="grid gap-4 md:grid-cols-2">
                <div>
                  <p class="text-[11px] font-semibold text-muted-foreground">Preço unitário por compra</p>
                  <PriceLine v-if="item.count > 1" :purchases="item.purchases" :color="item.color" detailed class="mt-1" />
                  <p v-else class="mt-1 text-sm">{{ money(item.lastUnitPrice) }} em uma compra só</p>
                  <p v-if="item.count > 1 && item.minUnitPrice != null && item.maxUnitPrice != null" class="mt-1 text-[11px] tabular text-muted-foreground">
                    Mais barato {{ money(item.minUnitPrice) }} · mais caro {{ money(item.maxUnitPrice) }} · {{ quantityLabel(item.quantity) }} un no total
                  </p>
                </div>
                <div>
                  <p class="text-[11px] font-semibold text-muted-foreground">Gasto por mês</p>
                  <MonthBars :values="item.months" :color="item.color" />
                </div>
              </div>
              <p class="mt-3 text-[11px] font-semibold text-muted-foreground">Compras</p>
              <ul class="mt-1 divide-y text-sm">
                <li v-for="p in [...item.purchases].reverse()" :key="`${p.transactionId}-${p.date}-${p.total}`" class="flex items-center gap-3 py-1.5">
                  <span class="w-14 shrink-0 tabular text-muted-foreground">{{ dateShort(p.date) }}</span>
                  <span class="min-w-0 flex-1 truncate">
                    <span class="font-medium">{{ p.merchant || p.description }}</span>
                    <span v-if="p.merchant && p.description !== item.description" class="text-muted-foreground"> · {{ p.description }}</span>
                  </span>
                  <span class="shrink-0 tabular text-muted-foreground">
                    <template v-if="p.quantity != null && p.quantity !== 1">{{ quantityLabel(p.quantity) }} × </template>{{ moneyPlain(p.unitPrice ?? p.total) }}
                  </span>
                  <Money :value="p.total" class="w-20 shrink-0 text-right font-semibold" />
                </li>
              </ul>
            </div>
          </li>
        </ul>
      </div>

      <div v-else class="rounded-2xl border border-dashed">
        <EmptyState
          :icon="ShoppingBasket" :title="data.items.length ? 'Nenhum item com esse nome' : 'Nenhum item no período'"
          :description="data.items.length ? 'Tente outra palavra ou amplie o período.' : 'Os itens vêm da leitura de comprovantes: no formulário de despesa, use “Ler comprovante”.'"
        />
      </div>
    </template>
  </div>
</template>
