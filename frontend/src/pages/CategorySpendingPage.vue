<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ChartPie, ChevronDown, ChevronRight, TrendingDown, TrendingUp } from 'lucide-vue-next'
import { useCategoryReport } from '@/composables/queries'
import type { CategorySpending } from '@/api/types'
import CategoryIcon from '@/components/app/CategoryIcon.vue'
import EmptyState from '@/components/app/EmptyState.vue'
import Money from '@/components/app/Money.vue'
import MonthBars from '@/components/app/MonthBars.vue'
import { Skeleton } from '@/components/ui/skeleton'
import { notifyError } from '@/lib/notify'
import { cn } from '@/lib/utils'
import { usePeriodStore } from '@/stores/period'

const period = usePeriodStore()

const { data, isPending: loading, error } = useCategoryReport(() => period.month)
const expanded = ref(new Set<string>())
watch(error, (err) => { if (err) notifyError(err) })

function keyOf(item: CategorySpending) {
  return String(item.categoryId ?? 'none')
}

function toggle(item: CategorySpending) {
  const k = keyOf(item)
  if (expanded.value.has(k)) expanded.value.delete(k)
  else expanded.value.add(k)
}

/** Variação em relação ao mês anterior, em %, ou null quando não há base de comparação. */
function change(current: number, previous: number | null | undefined) {
  if (previous == null || previous <= 0) return null
  return ((current - previous) / previous) * 100
}

const totalChange = computed(() => (data.value ? change(data.value.total, data.value.previousTotal) : null))
/** Linhas da lista com a variação já calculada. */
const rows = computed(() => (data.value?.items ?? []).map((item) => ({ item, key: keyOf(item), change: change(item.total, item.previousTotal) })))

function categoryQuery(id: number | null) {
  return { categoria: id ?? 'nenhuma', tipo: 'EXPENSE' }
}
</script>

<template>
  <div v-if="loading && !data" class="space-y-4">
    <Skeleton class="h-28 w-full rounded-2xl" />
    <Skeleton class="h-64 w-full rounded-2xl" />
  </div>

  <div v-else-if="data" class="space-y-6">
    <section class="rounded-2xl border bg-card p-4 md:p-5">
      <div class="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p class="text-xs font-semibold text-muted-foreground">Despesas de {{ period.label }}</p>
          <Money :value="data.total" type="EXPENSE" class="block text-2xl font-bold md:text-3xl" />
          <p class="mt-0.5 text-xs text-muted-foreground">
            {{ data.count }} {{ data.count === 1 ? 'lançamento' : 'lançamentos' }} em {{ data.items.length }} {{ data.items.length === 1 ? 'categoria' : 'categorias' }}, pagos e pendentes
          </p>
        </div>
        <div class="flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground sm:justify-end sm:text-right">
          <p>Mês anterior <Money :value="data.previousTotal" class="font-semibold text-foreground" /></p>
          <p v-if="totalChange !== null" :class="cn('inline-flex items-center gap-1 font-semibold', totalChange > 0 ? 'text-expense' : totalChange < 0 ? 'text-income' : '')">
            <component :is="totalChange > 0 ? TrendingUp : TrendingDown" v-if="totalChange !== 0" class="size-3.5" />
            {{ totalChange > 0 ? '+' : '' }}{{ totalChange.toFixed(0) }}%
          </p>
        </div>
      </div>
      <div v-if="data.items.length" class="mt-4 flex h-3 w-full gap-px overflow-hidden rounded-full bg-muted" role="img" aria-label="Participação de cada categoria">
        <span
          v-for="item in data.items" :key="keyOf(item)" :title="`${item.name}: ${item.percent.toFixed(0)}%`"
          class="h-full" :style="{ width: `${item.percent}%`, backgroundColor: item.color }"
        />
      </div>
    </section>

    <div v-if="data.items.length" class="rounded-2xl border bg-card">
      <ul class="divide-y">
        <li v-for="{ item, key, change: delta } in rows" :key="key">
          <div class="flex items-center gap-3 px-4 py-3">
            <button
              type="button" class="flex min-w-0 flex-1 items-center gap-3 rounded-md text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              :aria-expanded="expanded.has(key)" @click="toggle(item)"
            >
              <CategoryIcon :icon="item.icon" :color="item.color" />
              <span class="min-w-0 flex-1">
                <span class="flex items-baseline justify-between gap-3">
                  <span class="truncate text-sm font-semibold">{{ item.name }}</span>
                  <Money :value="item.total" class="text-sm font-bold" />
                </span>
                <span class="mt-1.5 flex items-center gap-2">
                  <span class="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
                    <span class="block h-full rounded-full" :style="{ width: `${Math.max(2, item.percent)}%`, backgroundColor: item.color }" />
                  </span>
                  <span class="w-10 text-right text-[11px] tabular text-muted-foreground">{{ item.percent.toFixed(0) }}%</span>
                </span>
                <span class="mt-1 flex items-center justify-between gap-3 text-[11px] text-muted-foreground">
                  <span>{{ item.count }} {{ item.count === 1 ? 'lançamento' : 'lançamentos' }}</span>
                  <span v-if="delta === 0">igual ao mês anterior</span>
                  <span v-else-if="delta !== null" :class="cn('tabular font-semibold', delta > 0 ? 'text-expense' : 'text-income')">
                    {{ delta > 0 ? '+' : '' }}{{ delta.toFixed(0) }}% vs. mês anterior
                  </span>
                  <span v-else>sem gasto no mês anterior</span>
                </span>
              </span>
              <ChevronDown :class="cn('size-4 shrink-0 text-muted-foreground transition-transform', expanded.has(key) && 'rotate-180')" />
            </button>
          </div>

          <div v-if="expanded.has(key)" class="border-t bg-muted/30 px-4 pb-3 pt-2">
            <div v-if="item.children.length" class="mb-3">
              <p class="mb-1 text-[11px] font-semibold text-muted-foreground">Subcategorias</p>
              <ul class="divide-y">
                <li v-for="child in item.children" :key="child.categoryId ?? 'none'">
                  <RouterLink :to="{ name: 'transactions', query: categoryQuery(child.categoryId) }" class="flex items-center gap-2.5 py-1.5 text-sm hover:text-primary">
                    <CategoryIcon :icon="child.icon" :color="child.color" size="sm" />
                    <span class="min-w-0 flex-1">
                      <span class="flex items-baseline justify-between gap-3">
                        <span class="truncate font-semibold">{{ child.name }}</span>
                        <Money :value="child.total" class="font-semibold" />
                      </span>
                      <span class="mt-1 flex items-center gap-2">
                        <span class="h-1 flex-1 overflow-hidden rounded-full bg-muted">
                          <span class="block h-full rounded-full" :style="{ width: `${Math.max(2, child.percent)}%`, backgroundColor: child.color }" />
                        </span>
                        <span class="w-10 text-right text-[11px] tabular text-muted-foreground">{{ child.percent.toFixed(0) }}%</span>
                      </span>
                    </span>
                  </RouterLink>
                </li>
              </ul>
            </div>
            <p class="text-[11px] font-semibold text-muted-foreground">Últimos 6 meses</p>
            <MonthBars :values="item.history" :color="item.color" :selected="period.month" class="mx-auto max-w-md" @select="period.month = $event" />
            <RouterLink :to="{ name: 'transactions', query: categoryQuery(item.categoryId) }" class="mt-2 inline-flex items-center text-xs font-semibold text-primary">
              Ver lançamentos de {{ item.name }} <ChevronRight class="size-3.5" />
            </RouterLink>
          </div>
        </li>
      </ul>
    </div>

    <div v-else class="rounded-2xl border border-dashed">
      <EmptyState :icon="ChartPie" title="Sem despesas neste mês" description="Adicione uma despesa ou importe o extrato do banco.">
        <RouterLink :to="{ name: 'import' }" class="text-sm font-semibold text-primary">Importar extrato</RouterLink>
      </EmptyState>
    </div>
  </div>
</template>
