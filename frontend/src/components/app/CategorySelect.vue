<script setup lang="ts">
import { computed } from 'vue'
import type { TransactionType } from '@/api/types'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import CategoryIcon from './CategoryIcon.vue'
import { useCatalogStore } from '@/stores/catalog'

const props = defineProps<{
  modelValue: number | null
  type: TransactionType
  id?: string
  allowNone?: boolean
  size?: 'sm' | 'default'
}>()
const emit = defineEmits<{ 'update:modelValue': [value: number | null] }>()

const catalog = useCatalogStore()
const options = computed(() => (props.type === 'EXPENSE' ? catalog.expenseCategories : catalog.incomeCategories))
const selected = computed(() => catalog.category(props.modelValue))
const value = computed(() => (props.modelValue == null ? 'none' : String(props.modelValue)))

function onUpdate(v: unknown) {
  emit('update:modelValue', v === 'none' || v == null ? null : Number(v))
}
</script>

<template>
  <Select :model-value="value" @update:model-value="onUpdate">
    <SelectTrigger :id="id" :size="size ?? 'default'" class="w-full">
      <SelectValue placeholder="Escolha a categoria">
        <span v-if="selected" class="flex items-center gap-2">
          <CategoryIcon :icon="selected.icon" :color="selected.color" size="sm" />
          <span class="truncate">{{ selected.name }}</span>
        </span>
        <span v-else class="text-muted-foreground">{{ allowNone === false ? 'Escolha a categoria' : 'Sem categoria' }}</span>
      </SelectValue>
    </SelectTrigger>
    <SelectContent>
      <SelectItem v-if="allowNone !== false" value="none">
        <span class="text-muted-foreground">Sem categoria</span>
      </SelectItem>
      <SelectItem v-for="c in options" :key="c.id" :value="String(c.id)">
        <span class="flex items-center gap-2">
          <CategoryIcon :icon="c.icon" :color="c.color" size="sm" />
          {{ c.name }}
        </span>
      </SelectItem>
    </SelectContent>
  </Select>
</template>
