<script setup lang="ts">
import { ref, watch } from 'vue'
import { CalendarDays } from 'lucide-vue-next'
import { formatDateInput, parseDateInput } from '@/lib/format'
import { cn } from '@/lib/utils'

/**
 * Campo de data sempre em dd/mm/aaaa, independente do idioma do navegador.
 * O v-model é a data ISO (aaaa-mm-dd) ou '' quando vazia/incompleta.
 * O seletor nativo fica escondido atrás do ícone de calendário.
 */
const props = withDefaults(defineProps<{
  modelValue: string
  id?: string
  disabled?: boolean
  min?: string
  max?: string
  class?: string
}>(), { disabled: false })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const text = ref(formatDateInput(props.modelValue))
const input = ref<HTMLInputElement | null>(null)
const native = ref<HTMLInputElement | null>(null)

watch(() => props.modelValue, (v) => {
  if (v !== parseDateInput(text.value)) text.value = formatDateInput(v)
})

function onInput(e: Event) {
  const el = e.target as HTMLInputElement
  const digits = el.value.replace(/\D/g, '').slice(0, 8)
  const parts = [digits.slice(0, 2), digits.slice(2, 4), digits.slice(4, 8)].filter(Boolean)
  text.value = parts.join('/')
  const iso = digits.length === 8 ? parseDateInput(text.value) : ''
  if (iso !== props.modelValue) emit('update:modelValue', iso)
  requestAnimationFrame(() => input.value?.setSelectionRange(text.value.length, text.value.length))
}

function onBlur() {
  // Texto incompleto ou inválido não vira data: limpa para o erro de validação aparecer.
  if (text.value && !parseDateInput(text.value)) {
    text.value = ''
    if (props.modelValue) emit('update:modelValue', '')
  }
}

function onNative(e: Event) {
  const iso = (e.target as HTMLInputElement).value
  text.value = formatDateInput(iso)
  emit('update:modelValue', iso)
}

function openPicker() {
  const el = native.value
  if (!el) return
  try {
    el.showPicker()
  } catch {
    el.focus()
    el.click()
  }
}
</script>

<template>
  <div :class="cn('relative', props.class)">
    <input
      :id="id"
      ref="input"
      :value="text"
      type="text"
      inputmode="numeric"
      autocomplete="off"
      placeholder="dd/mm/aaaa"
      maxlength="10"
      :disabled="disabled"
      data-slot="input"
      :class="cn(
        'placeholder:text-muted-foreground selection:bg-primary selection:text-primary-foreground dark:bg-input/30 border-input h-9 w-full min-w-0 rounded-md border bg-transparent py-1 pr-10 pl-3 text-base tabular shadow-xs transition-[color,box-shadow] outline-none disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50 md:text-sm',
        'focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-3',
      )"
      @input="onInput"
      @blur="onBlur"
    />
    <button
      type="button"
      tabindex="-1"
      aria-label="Abrir calendário"
      :disabled="disabled"
      class="absolute inset-y-0 right-0 flex w-10 items-center justify-center rounded-r-md text-muted-foreground hover:text-foreground disabled:pointer-events-none disabled:opacity-50"
      @click="openPicker"
    >
      <CalendarDays class="size-4" />
    </button>
    <!-- Seletor nativo: invisível, só serve de âncora para o calendário do navegador. -->
    <input
      ref="native"
      type="date"
      tabindex="-1"
      aria-hidden="true"
      :value="modelValue"
      :min="min"
      :max="max"
      :disabled="disabled"
      class="pointer-events-none absolute inset-y-0 right-0 w-10 opacity-0"
      @input="onNative"
      @change="onNative"
    />
  </div>
</template>
