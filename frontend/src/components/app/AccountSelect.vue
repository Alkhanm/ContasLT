<script setup lang="ts">
import { computed } from 'vue'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useCatalogStore } from '@/stores/catalog'

const props = defineProps<{ modelValue: string | null; id?: string; allowAll?: boolean; size?: 'sm' | 'default' }>()
const emit = defineEmits<{ 'update:modelValue': [value: string | null] }>()

const catalog = useCatalogStore()
const value = computed(() => (props.modelValue == null ? 'all' : String(props.modelValue)))
const selected = computed(() => catalog.account(props.modelValue))

function onUpdate(v: unknown) {
  emit('update:modelValue', v === 'all' || v == null ? null : String(v))
}
</script>

<template>
  <Select :model-value="value" @update:model-value="onUpdate">
    <SelectTrigger :id="id" :size="size ?? 'default'" class="w-full">
      <SelectValue placeholder="Escolha a conta">
        <span v-if="selected" class="flex items-center gap-2">
          <span class="size-2.5 rounded-full" :style="{ backgroundColor: selected.color }" />
          <span class="truncate">{{ selected.name }}</span>
        </span>
        <span v-else-if="allowAll">Todas as contas</span>
      </SelectValue>
    </SelectTrigger>
    <SelectContent>
      <SelectItem v-if="allowAll" value="all">Todas as contas</SelectItem>
      <SelectItem v-for="a in catalog.activeAccounts" :key="a.id" :value="String(a.id)">
        <span class="flex items-center gap-2">
          <span class="size-2.5 rounded-full" :style="{ backgroundColor: a.color }" />
          {{ a.name }}
        </span>
      </SelectItem>
    </SelectContent>
  </Select>
</template>
