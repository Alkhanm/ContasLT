<script setup lang="ts">
import { computed } from 'vue'
import { Plus, X } from 'lucide-vue-next'
import type { TransactionItemInput } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import Money from './Money.vue'
import { moneyPlain, parseMoneyInput } from '@/lib/format'

const props = defineProps<{ modelValue: TransactionItemInput[]; amount: number | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: TransactionItemInput[]]; useSum: [value: number] }>()

const sum = computed(() => Math.round(props.modelValue.reduce((s, i) => s + (i.total || 0), 0) * 100) / 100)
const difference = computed(() => (props.amount == null ? 0 : Math.round((props.amount - sum.value) * 100) / 100))

function update(index: number, patch: Partial<TransactionItemInput>) {
  const next = props.modelValue.map((it, i) => (i === index ? { ...it, ...patch } : it))
  emit('update:modelValue', next)
}

function remove(index: number) {
  emit('update:modelValue', props.modelValue.filter((_, i) => i !== index))
}

function add() {
  emit('update:modelValue', [...props.modelValue, { description: '', quantity: 1, unitPrice: null, total: 0 }])
}

function onTotal(index: number, e: Event) {
  const value = parseMoneyInput((e.target as HTMLInputElement).value) ?? 0
  update(index, { total: value })
}

function onQuantity(index: number, e: Event) {
  const raw = (e.target as HTMLInputElement).value.replace(',', '.')
  const q = raw === '' ? null : Number(raw)
  update(index, { quantity: q === null || Number.isNaN(q) ? null : q })
}
</script>

<template>
  <div class="space-y-2">
    <ul v-if="modelValue.length" class="divide-y rounded-lg border">
      <li v-for="(item, i) in modelValue" :key="i" class="space-y-1.5 px-2 py-2">
        <div class="flex items-center gap-1">
          <Input
            :model-value="item.description" class="h-8 min-w-0 flex-1 text-sm" placeholder="Produto" maxlength="140"
            :aria-label="`Item ${i + 1}`" @update:model-value="update(i, { description: String($event) })"
          />
          <button type="button" class="inline-flex size-8 shrink-0 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-destructive" :aria-label="`Remover item ${i + 1}`" @click="remove(i)">
            <X class="size-4" />
          </button>
        </div>
        <div class="flex items-center justify-end gap-3 text-xs text-muted-foreground">
          <label class="flex items-center gap-1.5">
            qtd
            <input
              :value="item.quantity ?? ''" inputmode="decimal" :aria-label="`Quantidade do item ${i + 1}`"
              class="h-7 w-14 rounded-md border bg-transparent px-2 text-right text-sm text-foreground tabular outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              @change="onQuantity(i, $event)"
            >
          </label>
          <label class="flex items-center gap-1.5">
            R$
            <input
              :value="moneyPlain(item.total)" inputmode="decimal" :aria-label="`Valor do item ${i + 1}`"
              class="h-7 w-24 rounded-md border bg-transparent px-2 text-right text-sm text-foreground tabular outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              @change="onTotal(i, $event)"
            >
          </label>
        </div>
      </li>
    </ul>

    <div class="flex items-center justify-between gap-3 text-xs">
      <Button type="button" variant="ghost" size="sm" class="h-7 px-2 text-xs" @click="add"><Plus /> Item</Button>
      <span v-if="modelValue.length" class="text-muted-foreground">
        Soma <Money :value="sum" class="font-semibold text-foreground" />
        <template v-if="difference !== 0">
          · <span class="text-warning">{{ difference > 0 ? 'faltam' : 'excedem' }} <Money :value="Math.abs(difference)" class="text-warning" /></span>
          <button type="button" class="ml-1 font-semibold text-primary underline-offset-2 hover:underline" @click="emit('useSum', sum)">usar a soma</button>
        </template>
      </span>
    </div>
  </div>
</template>
