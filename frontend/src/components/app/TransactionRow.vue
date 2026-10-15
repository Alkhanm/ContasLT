<script setup lang="ts">
import { computed } from 'vue'
import { Check, Repeat } from 'lucide-vue-next'
import type { Transaction } from '@/api/types'
import CategoryIcon from './CategoryIcon.vue'
import Money from './Money.vue'
import { dateShort, dueLabel, dueStatus } from '@/lib/format'
import { cn } from '@/lib/utils'

const props = withDefaults(defineProps<{
  transaction: Transaction
  showDate?: boolean
  showAccount?: boolean
  busy?: boolean
}>(), { showDate: false, showAccount: true, busy: false })

const emit = defineEmits<{ open: [tx: Transaction]; togglePaid: [tx: Transaction] }>()

const status = computed(() => dueStatus(props.transaction))
const statusClass = computed(() => ({
  paid: 'text-muted-foreground',
  overdue: 'text-expense font-semibold',
  today: 'text-warning font-semibold',
  soon: 'text-warning',
  scheduled: 'text-muted-foreground',
}[status.value]))

const meta = computed(() => {
  const t = props.transaction
  const parts: string[] = []
  if (props.showDate) parts.push(dateShort(t.dueDate))
  if (t.categoryName) parts.push(t.categoryName)
  if (t.installmentNumber && t.installmentTotal) parts.push(`${t.installmentNumber}/${t.installmentTotal}`)
  return parts.join(' · ')
})

const toggleTitle = computed(() => props.transaction.paid
  ? (props.transaction.type === 'INCOME' ? 'Marcar como não recebido' : 'Marcar como não pago')
  : (props.transaction.type === 'INCOME' ? 'Marcar como recebido' : 'Marcar como pago'))
</script>

<template>
  <div class="group flex items-center gap-3 py-2.5">
    <button type="button" class="flex min-w-0 flex-1 items-center gap-3 text-left outline-none rounded-md focus-visible:ring-3 focus-visible:ring-ring/50" @click="emit('open', transaction)">
      <CategoryIcon :icon="transaction.categoryIcon" :color="transaction.categoryColor" />
      <span class="min-w-0 flex-1">
        <span class="flex items-center gap-1.5">
          <span :class="cn('truncate font-semibold', transaction.paid && 'text-muted-foreground')">{{ transaction.description }}</span>
          <Repeat v-if="transaction.recurringBillId" class="size-3.5 shrink-0 text-muted-foreground" aria-label="Recorrente" />
        </span>
        <span class="flex items-baseline justify-between gap-3 text-xs text-muted-foreground">
          <span class="truncate">
            {{ meta }}<template v-if="meta"> · </template><span :class="statusClass">{{ dueLabel(transaction) }}</span><span v-if="showAccount" class="hidden sm:inline"> · {{ transaction.accountName }}</span>
          </span>
          <Money :value="transaction.amount" :type="transaction.type" signed class="text-sm font-semibold sm:hidden" />
        </span>
      </span>
      <Money :value="transaction.amount" :type="transaction.type" signed class="hidden text-sm font-semibold sm:inline" />
    </button>
    <button
      type="button"
      :title="toggleTitle"
      :aria-label="toggleTitle"
      :aria-pressed="transaction.paid"
      :disabled="busy"
      :class="cn('inline-flex size-8 shrink-0 items-center justify-center rounded-full border transition-colors outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
        transaction.paid ? 'border-income bg-income text-white' : 'border-border text-transparent hover:border-income hover:text-income/60',
        busy && 'opacity-50')"
      @click.stop="emit('togglePaid', transaction)"
    >
      <Check class="size-4" :stroke-width="3" />
    </button>
  </div>
</template>
