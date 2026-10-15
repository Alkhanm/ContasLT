<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { CalendarCheck, ChevronRight, Wallet } from 'lucide-vue-next'
import { dashboardApi, transactionsApi } from '@/api'
import type { CategoryTotal, Dashboard, Transaction } from '@/api/types'
import CategoryBars from '@/components/app/CategoryBars.vue'
import EmptyState from '@/components/app/EmptyState.vue'
import HistoryChart from '@/components/app/HistoryChart.vue'
import Money from '@/components/app/Money.vue'
import SectionTitle from '@/components/app/SectionTitle.vue'
import TransactionRow from '@/components/app/TransactionRow.vue'
import { Skeleton } from '@/components/ui/skeleton'
import { accountTypeLabels } from '@/lib/format'
import { notifyError, notifySuccess } from '@/lib/notify'
import { useCatalogStore } from '@/stores/catalog'
import { usePeriodStore } from '@/stores/period'
import { useUiStore } from '@/stores/ui'

const period = usePeriodStore()
const ui = useUiStore()
const catalog = useCatalogStore()
const router = useRouter()

const data = ref<Dashboard | null>(null)
const loading = ref(true)
const busyIds = ref(new Set<number>())

async function load() {
  loading.value = data.value === null || data.value.month !== period.month
  try {
    data.value = await dashboardApi.get(period.month)
  } catch (err) {
    notifyError(err)
  } finally {
    loading.value = false
  }
}

watch([() => period.month, () => ui.dataVersion], load, { immediate: true })

const paidShare = computed(() => {
  if (!data.value || data.value.expense <= 0) return 0
  return Math.min(100, (data.value.paidExpense / data.value.expense) * 100)
})

const bills = computed(() => {
  if (!data.value) return []
  const seen = new Set<number>()
  return [...data.value.overdue, ...data.value.upcoming].filter((t) => (seen.has(t.id) ? false : (seen.add(t.id), true)))
})
const MAX_BILLS = 8
const visibleBills = computed(() => bills.value.slice(0, MAX_BILLS))
const hiddenBills = computed(() => Math.max(0, bills.value.length - MAX_BILLS))

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

function openCategory(item: CategoryTotal) {
  router.push({ name: 'transactions', query: { categoria: item.categoryId ?? 'nenhuma', tipo: 'EXPENSE' } })
}
</script>

<template>
  <div v-if="loading && !data" class="space-y-4">
    <Skeleton class="h-28 w-full rounded-xl" />
    <Skeleton class="h-48 w-full rounded-xl" />
    <Skeleton class="h-48 w-full rounded-xl" />
  </div>

  <div v-else-if="data" class="grid gap-6 md:grid-cols-5">
    <div class="space-y-6 md:col-span-3">
      <section class="rounded-2xl border bg-card p-4 md:p-5">
        <div class="grid grid-cols-3 divide-x">
          <div class="pr-3">
            <p class="text-xs font-semibold text-muted-foreground">Receitas</p>
            <Money :value="data.income" type="INCOME" class="block text-[17px] font-bold md:text-xl" />
          </div>
          <div class="px-3">
            <p class="text-xs font-semibold text-muted-foreground">Despesas</p>
            <Money :value="data.expense" type="EXPENSE" class="block text-[17px] font-bold md:text-xl" />
          </div>
          <div class="pl-3">
            <p class="text-xs font-semibold text-muted-foreground">Balanço</p>
            <Money :value="data.balance" class="block text-[17px] font-bold md:text-xl" />
          </div>
        </div>
        <div class="mt-4">
          <div class="h-2 overflow-hidden rounded-full bg-muted" role="progressbar" :aria-valuenow="Math.round(paidShare)" aria-valuemin="0" aria-valuemax="100" aria-label="Despesas pagas">
            <div class="h-full rounded-full bg-primary transition-[width] duration-500" :style="{ width: `${paidShare}%` }" />
          </div>
          <p class="mt-1.5 text-xs text-muted-foreground">
            <Money :value="data.paidExpense" class="font-semibold text-foreground" /> pagos de <Money :value="data.expense" />
            <template v-if="data.pendingExpense > 0"> · faltam <Money :value="data.pendingExpense" class="font-semibold text-foreground" /></template>
          </p>
        </div>
      </section>

      <section>
        <SectionTitle title="Contas a pagar" :hint="bills.length ? 'Vencidas e dos próximos 7 dias' : undefined">
          <RouterLink :to="{ name: 'transactions', query: { status: 'pendente' } }" class="flex items-center text-xs font-semibold text-primary">
            Ver todas <ChevronRight class="size-3.5" />
          </RouterLink>
        </SectionTitle>
        <div v-if="bills.length" class="divide-y rounded-2xl border bg-card px-4">
          <TransactionRow
            v-for="t in visibleBills" :key="t.id" :transaction="t" show-date :show-account="false"
            :busy="busyIds.has(t.id)" @open="ui.editTransaction" @toggle-paid="togglePaid"
          />
          <RouterLink v-if="hiddenBills" :to="{ name: 'transactions', query: { status: 'pendente' } }" class="block py-3 text-center text-sm font-semibold text-primary">
            Mais {{ hiddenBills }} {{ hiddenBills === 1 ? 'pendente' : 'pendentes' }}
          </RouterLink>
        </div>
        <div v-else class="rounded-2xl border border-dashed">
          <EmptyState :icon="CalendarCheck" title="Nada vence nos próximos 7 dias" description="Contas recorrentes aparecem aqui automaticamente quando o vencimento se aproxima." />
        </div>
      </section>

      <section>
        <SectionTitle title="Últimos 6 meses" />
        <div class="rounded-2xl border bg-card p-4">
          <HistoryChart :history="data.history" :selected="period.month" @select="period.month = $event" />
        </div>
      </section>
    </div>

    <div class="space-y-6 md:col-span-2">
      <section>
        <SectionTitle title="Despesas por categoria" />
        <div v-if="data.byCategory.length" class="rounded-2xl border bg-card px-4">
          <CategoryBars :items="data.byCategory" @select="openCategory" />
        </div>
        <div v-else class="rounded-2xl border border-dashed">
          <EmptyState title="Sem despesas neste mês" description="Adicione uma despesa ou importe o extrato do banco.">
            <RouterLink :to="{ name: 'import' }" class="text-sm font-semibold text-primary">Importar extrato</RouterLink>
          </EmptyState>
        </div>
      </section>

      <section>
        <SectionTitle title="Contas">
          <RouterLink :to="{ name: 'accounts' }" class="flex items-center text-xs font-semibold text-primary">
            Gerenciar <ChevronRight class="size-3.5" />
          </RouterLink>
        </SectionTitle>
        <div class="rounded-2xl border bg-card px-4">
          <ul class="divide-y">
            <li v-for="a in data.accounts" :key="a.id" class="flex items-center gap-3 py-3">
              <span class="inline-flex size-8 items-center justify-center rounded-full" :style="{ backgroundColor: a.color + '22', color: a.color }">
                <Wallet class="size-4" />
              </span>
              <span class="min-w-0 flex-1">
                <span class="block truncate text-sm font-semibold">{{ a.name }}</span>
                <span class="block text-xs text-muted-foreground">{{ accountTypeLabels[a.type] }}</span>
              </span>
              <Money :value="a.balance" class="text-sm font-semibold" />
            </li>
          </ul>
          <div class="flex items-center justify-between border-t py-3">
            <span class="text-sm font-semibold text-muted-foreground">Saldo total</span>
            <Money :value="data.currentBalance" class="text-base font-bold" />
          </div>
        </div>
      </section>
    </div>
  </div>
</template>
