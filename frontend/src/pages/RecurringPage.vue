<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Plus, Repeat } from 'lucide-vue-next'
import { recurringApi } from '@/api'
import { useRecurringBills } from '@/composables/queries'
import type { RecurringBill } from '@/api/types'
import CategoryIcon from '@/components/app/CategoryIcon.vue'
import EmptyState from '@/components/app/EmptyState.vue'
import Money from '@/components/app/Money.vue'
import RecurringFormSheet from '@/components/app/RecurringFormSheet.vue'
import SectionTitle from '@/components/app/SectionTitle.vue'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { dateShort, frequencyLabels } from '@/lib/format'
import { notifyError, notifySuccess } from '@/lib/notify'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const formOpen = ref(false)
const editing = ref<RecurringBill | null>(null)

const { data: billsData, isPending: loading, error, refetch: load } = useRecurringBills()
const bills = computed(() => billsData.value ?? [])
watch(error, (err) => { if (err) notifyError(err) })

const active = computed(() => bills.value.filter((b) => b.active))
const inactive = computed(() => bills.value.filter((b) => !b.active))
const monthlyExpense = computed(() => active.value
  .filter((b) => b.type === 'EXPENSE' && b.frequency === 'MONTHLY')
  .reduce((sum, b) => sum + b.amount, 0))
const monthlyIncome = computed(() => active.value
  .filter((b) => b.type === 'INCOME' && b.frequency === 'MONTHLY')
  .reduce((sum, b) => sum + b.amount, 0))

function openNew() {
  editing.value = null
  formOpen.value = true
}
function openEdit(bill: RecurringBill) {
  editing.value = bill
  formOpen.value = true
}

function onSaved() {
  load()
  ui.dataChanged()
}

async function toggleActive(bill: RecurringBill, value: boolean) {
  try {
    await recurringApi.update(bill.id, {
      description: bill.description, amount: bill.amount, type: bill.type, frequency: bill.frequency,
      startDate: bill.startDate, endDate: bill.endDate, occurrences: bill.occurrences,
      accountId: bill.accountId!, categoryId: bill.categoryId, notes: bill.notes, active: value,
    })
    notifySuccess(value ? 'Recorrência ativada' : 'Recorrência pausada')
    onSaved()
  } catch (err) {
    notifyError(err)
  }
}

function describe(b: RecurringBill) {
  const parts = [frequencyLabels[b.frequency]]
  if (b.occurrences) parts.push(`${b.occurrences} parcelas`)
  else if (b.endDate) parts.push(`até ${dateShort(b.endDate)}`)
  if (b.accountName) parts.push(b.accountName)
  return parts.join(' · ')
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
      <div v-if="active.length" class="flex flex-wrap gap-x-6 gap-y-2 text-sm">
        <div>
          <p class="text-xs font-semibold text-muted-foreground">Fixas por mês</p>
          <Money :value="monthlyExpense" type="EXPENSE" class="text-lg font-bold" />
        </div>
        <div v-if="monthlyIncome > 0">
          <p class="text-xs font-semibold text-muted-foreground">Receitas fixas</p>
          <Money :value="monthlyIncome" type="INCOME" class="text-lg font-bold" />
        </div>
      </div>
      <div v-else />
      <Button class="ml-auto shrink-0 rounded-full" @click="openNew">
        <Plus :stroke-width="2.5" /> Nova recorrência
      </Button>
    </div>

    <div v-if="loading" class="space-y-2">
      <Skeleton v-for="i in 4" :key="i" class="h-16 w-full rounded-xl" />
    </div>

    <template v-else>
      <section v-if="active.length">
        <SectionTitle title="Ativas" :hint="`${active.length} ${active.length === 1 ? 'conta' : 'contas'}`" />
        <ul class="divide-y rounded-2xl border bg-card px-4">
          <li v-for="b in active" :key="b.id" class="flex items-center gap-3 py-3">
            <button type="button" class="flex min-w-0 flex-1 items-center gap-3 text-left outline-none rounded-md focus-visible:ring-3 focus-visible:ring-ring/50" @click="openEdit(b)">
              <CategoryIcon :icon="b.categoryIcon" :color="b.categoryColor" />
              <span class="min-w-0 flex-1">
                <span class="block truncate font-semibold">{{ b.description }}</span>
                <span class="block truncate text-xs text-muted-foreground">
                  {{ describe(b) }}<template v-if="b.nextDueDate"> · próximo em <span class="font-semibold text-foreground">{{ dateShort(b.nextDueDate) }}</span></template>
                </span>
              </span>
              <Money :value="b.amount" :type="b.type" signed class="text-sm font-semibold" />
            </button>
            <Switch :model-value="b.active" :aria-label="`Ativar ${b.description}`" @update:model-value="toggleActive(b, $event)" />
          </li>
        </ul>
      </section>

      <section v-if="inactive.length">
        <SectionTitle title="Pausadas" />
        <ul class="divide-y rounded-2xl border bg-card px-4 opacity-80">
          <li v-for="b in inactive" :key="b.id" class="flex items-center gap-3 py-3">
            <button type="button" class="flex min-w-0 flex-1 items-center gap-3 text-left outline-none rounded-md focus-visible:ring-3 focus-visible:ring-ring/50" @click="openEdit(b)">
              <CategoryIcon :icon="b.categoryIcon" :color="b.categoryColor" />
              <span class="min-w-0 flex-1">
                <span class="block truncate font-semibold text-muted-foreground">{{ b.description }}</span>
                <span class="block truncate text-xs text-muted-foreground">{{ describe(b) }}</span>
              </span>
              <Money :value="b.amount" :type="b.type" signed muted class="text-sm font-semibold" />
            </button>
            <Switch :model-value="b.active" :aria-label="`Ativar ${b.description}`" @update:model-value="toggleActive(b, $event)" />
          </li>
        </ul>
      </section>

      <div v-if="!bills.length" class="rounded-2xl border border-dashed">
        <EmptyState :icon="Repeat" title="Nenhuma conta recorrente" description="Aluguel, internet, assinaturas, parcelas: cadastre uma vez e os vencimentos aparecem todo mês.">
          <Button @click="openNew"><Plus :stroke-width="2.5" /> Criar a primeira</Button>
        </EmptyState>
      </div>
    </template>

    <RecurringFormSheet v-model:open="formOpen" :bill="editing" @saved="onSaved" />
  </div>
</template>
