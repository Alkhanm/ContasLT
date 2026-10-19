<script setup lang="ts">
import { computed } from 'vue'
import { cn } from '@/lib/utils'

export type FormKind = 'EXPENSE' | 'INCOME' | 'TRANSFER'

const props = withDefaults(defineProps<{ modelValue: FormKind; disabled?: boolean; allowTransfer?: boolean }>(), {
  disabled: false, allowTransfer: false,
})
const emit = defineEmits<{ 'update:modelValue': [value: FormKind] }>()

const all: { value: FormKind; label: string; on: string }[] = [
  { value: 'EXPENSE', label: 'Despesa', on: 'bg-expense text-white' },
  { value: 'INCOME', label: 'Receita', on: 'bg-income text-white' },
  { value: 'TRANSFER', label: 'Transferência', on: 'bg-primary text-primary-foreground' },
]
const options = computed(() => (props.allowTransfer ? all : all.slice(0, 2)))
</script>

<template>
  <div role="radiogroup" :class="cn('grid gap-1 rounded-lg bg-muted p-1', options.length === 3 ? 'grid-cols-3' : 'grid-cols-2')">
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
