<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { moneyPlain, parseMoneyInput } from '@/lib/format'
import { cn } from '@/lib/utils'

const props = defineProps<{
  modelValue: number | null
  id?: string
  autofocus?: boolean
  invalid?: boolean
  class?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: number | null] }>()

const text = ref(props.modelValue == null ? '' : moneyPlain(props.modelValue))
const input = ref<HTMLInputElement | null>(null)

watch(() => props.modelValue, (v) => {
  const current = parseMoneyInput(text.value)
  if (v !== current) text.value = v == null ? '' : moneyPlain(v)
})

function onInput(e: Event) {
  const raw = (e.target as HTMLInputElement).value
  const value = parseMoneyInput(raw)
  text.value = value == null ? '' : moneyPlain(value)
  emit('update:modelValue', value)
  requestAnimationFrame(() => input.value?.setSelectionRange(text.value.length, text.value.length))
}

const sizeClass = computed(() => (text.value.length > 12 ? 'text-2xl' : 'text-3xl'))
</script>

<template>
  <div :class="cn('flex items-baseline gap-2 rounded-lg border bg-card px-4 py-3 focus-within:ring-3 focus-within:ring-ring/40', invalid && 'border-destructive', props.class)">
    <span class="text-sm font-semibold text-muted-foreground">R$</span>
    <input
      :id="id"
      ref="input"
      :value="text"
      type="text"
      inputmode="numeric"
      autocomplete="off"
      placeholder="0,00"
      :autofocus="autofocus"
      :class="cn('w-full min-w-0 bg-transparent font-bold tabular outline-none placeholder:text-muted-foreground/60', sizeClass)"
      @input="onInput"
    />
  </div>
</template>
