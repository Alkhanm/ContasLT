<script setup lang="ts">
import { computed } from 'vue'
import { money } from '@/lib/format'
import { cn } from '@/lib/utils'

const props = withDefaults(defineProps<{
  value: number
  /** Colore conforme o tipo e mostra o sinal. */
  type?: 'EXPENSE' | 'INCOME' | 'NEUTRAL'
  signed?: boolean
  muted?: boolean
  class?: string
}>(), { type: 'NEUTRAL', signed: false, muted: false })

const text = computed(() => {
  const abs = money(Math.abs(props.value))
  if (!props.signed) return props.type === 'NEUTRAL' && props.value < 0 ? '−' + abs : abs
  if (props.type === 'EXPENSE') return '−' + abs
  if (props.type === 'INCOME') return '+' + abs
  return props.value < 0 ? '−' + abs : abs
})

const color = computed(() => {
  if (props.muted) return 'text-muted-foreground'
  if (props.type === 'EXPENSE') return 'text-expense'
  if (props.type === 'INCOME') return 'text-income'
  if (props.value < 0) return 'text-expense'
  return 'text-foreground'
})
</script>

<template>
  <span :class="cn('tabular whitespace-nowrap', color, props.class)">{{ text }}</span>
</template>
