<script setup lang="ts">
import { computed } from 'vue'
import type { Category, TransactionType } from '@/api/types'
import { Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectTrigger, SelectValue } from '@/components/ui/select'
import CategoryIcon from './CategoryIcon.vue'
import { cn } from '@/lib/utils'
import { useCatalogStore } from '@/stores/catalog'

/**
 * Seleção de categoria em cascata: primeiro a categoria, depois a subcategoria (quando a escolhida tem alguma).
 * O valor é um id só: o da subcategoria, ou o da categoria quando se fica no "geral".
 * Sem `type`, lista despesas e receitas agrupadas (uso em filtros).
 */
const props = defineProps<{
  modelValue: number | null
  type?: TransactionType
  id?: string
  allowNone?: boolean
  size?: 'sm' | 'default'
  /** Texto da opção vazia (padrão "Sem categoria"). */
  noneLabel?: string
  /** Texto da opção que mantém só a categoria pai (padrão "Geral"). */
  childAllLabel?: string
  /** Filtros: oferece "Sem categoria" como opção à parte (v-model:uncategorized). */
  allowUncategorized?: boolean
  uncategorized?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: number | null]; 'update:uncategorized': [value: boolean] }>()

const catalog = useCatalogStore()
const selected = computed(() => catalog.category(props.modelValue))
const rootId = computed(() => (selected.value ? selected.value.parentId ?? selected.value.id : null))
const root = computed(() => catalog.category(rootId.value))
const roots = computed(() => catalog.rootCategories.filter((c) => !props.type || c.type === props.type))
const groups = computed(() => [
  { label: 'Despesas', items: roots.value.filter((c) => c.type === 'EXPENSE') },
  { label: 'Receitas', items: roots.value.filter((c) => c.type === 'INCOME') },
].filter((g) => g.items.length))
const children = computed<Category[]>(() =>
  catalog.childrenOf(rootId.value, true).filter((c) => !c.archived || c.id === props.modelValue))
const isChild = computed(() => selected.value?.parentId != null)

const rootValue = computed(() => (props.uncategorized ? 'uncategorized' : rootId.value == null ? 'none' : String(rootId.value)))
const childValue = computed(() => (isChild.value ? String(props.modelValue) : 'all'))
const noneText = computed(() => props.noneLabel ?? (props.allowNone === false ? 'Escolha a categoria' : 'Sem categoria'))

function onRoot(v: unknown) {
  if (props.allowUncategorized) emit('update:uncategorized', v === 'uncategorized')
  emit('update:modelValue', v === 'none' || v === 'uncategorized' || v == null ? null : Number(v))
}
function onChild(v: unknown) {
  emit('update:modelValue', v === 'all' || v == null ? rootId.value : Number(v))
}
</script>

<template>
  <div :class="cn('grid gap-2', children.length && !uncategorized ? 'grid-cols-2' : 'grid-cols-1')">
    <Select :model-value="rootValue" @update:model-value="onRoot">
      <SelectTrigger :id="id" :size="size ?? 'default'" class="w-full">
        <SelectValue placeholder="Escolha a categoria">
          <span v-if="uncategorized" class="flex items-center gap-2">
            <CategoryIcon icon="circle-dashed" color="#9CA3AF" size="sm" />
            <span class="truncate">Sem categoria</span>
          </span>
          <span v-else-if="root" class="flex items-center gap-2">
            <CategoryIcon :icon="root.icon" :color="root.color" size="sm" />
            <span class="truncate">{{ root.name }}</span>
          </span>
          <span v-else class="text-muted-foreground">{{ noneText }}</span>
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        <SelectItem v-if="allowNone !== false" value="none">
          <span class="text-muted-foreground">{{ noneLabel ?? 'Sem categoria' }}</span>
        </SelectItem>
        <SelectItem v-if="allowUncategorized" value="uncategorized">
          <span class="flex items-center gap-2">
            <CategoryIcon icon="circle-dashed" color="#9CA3AF" size="sm" />
            Sem categoria
          </span>
        </SelectItem>
        <template v-if="type">
          <SelectItem v-for="c in roots" :key="c.id" :value="String(c.id)">
            <span class="flex items-center gap-2">
              <CategoryIcon :icon="c.icon" :color="c.color" size="sm" />
              {{ c.name }}
            </span>
          </SelectItem>
        </template>
        <SelectGroup v-for="g in groups" v-else :key="g.label">
          <SelectLabel>{{ g.label }}</SelectLabel>
          <SelectItem v-for="c in g.items" :key="c.id" :value="String(c.id)">
            <span class="flex items-center gap-2">
              <CategoryIcon :icon="c.icon" :color="c.color" size="sm" />
              {{ c.name }}
            </span>
          </SelectItem>
        </SelectGroup>
      </SelectContent>
    </Select>

    <Select v-if="children.length && !uncategorized" :model-value="childValue" @update:model-value="onChild">
      <SelectTrigger :size="size ?? 'default'" class="w-full" :aria-label="`Subcategoria de ${root?.name ?? ''}`">
        <SelectValue>
          <span v-if="isChild && selected" class="flex items-center gap-2">
            <CategoryIcon :icon="selected.icon" :color="selected.color" size="sm" />
            <span class="truncate">{{ selected.name }}</span>
          </span>
          <span v-else class="text-muted-foreground">{{ childAllLabel ?? 'Geral' }}</span>
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="all">
          <span class="text-muted-foreground">{{ childAllLabel ?? 'Geral' }}</span>
        </SelectItem>
        <SelectItem v-for="c in children" :key="c.id" :value="String(c.id)">
          <span class="flex items-center gap-2">
            <CategoryIcon :icon="c.icon" :color="c.color" size="sm" />
            {{ c.name }}<span v-if="c.archived" class="text-xs text-muted-foreground"> (arquivada)</span>
          </span>
        </SelectItem>
      </SelectContent>
    </Select>
  </div>
</template>
