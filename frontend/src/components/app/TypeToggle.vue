<script setup lang="ts">
import type { TransactionType } from '@/api/types'
import { cn } from '@/lib/utils'

defineProps<{ modelValue: TransactionType; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: TransactionType] }>()

const options: { value: TransactionType; label: string; on: string }[] = [
  { value: 'EXPENSE', label: 'Despesa', on: 'bg-expense text-white' },
  { value: 'INCOME', label: 'Receita', on: 'bg-income text-white' },
]
</script>

<template>
  <div role="radiogroup" class="grid grid-cols-2 gap-1 rounded-lg bg-muted p-1">
    <button
      v-for="o in options"
      :key="o.value"
      type="button"
      role="radio"
      :aria-checked="modelValue === o.value"
      :disabled="disabled"
      :class="cn('h-9 rounded-md text-sm font-semibold transition-colors focus-visible:ring-3 focus-visible:ring-ring/50 outline-none',
        modelValue === o.value ? o.on : 'text-muted-foreground hover:text-foreground', disabled && 'opacity-60')"
      @click="emit('update:modelValue', o.value)"
    >
      {{ o.label }}
    </button>
  </div>
</template>
