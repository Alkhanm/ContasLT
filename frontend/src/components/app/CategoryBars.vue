<script setup lang="ts">
import type { CategoryTotal } from '@/api/types'
import CategoryIcon from './CategoryIcon.vue'
import Money from './Money.vue'

defineProps<{ items: CategoryTotal[] }>()
const emit = defineEmits<{ select: [item: CategoryTotal] }>()
</script>

<template>
  <ul class="divide-y">
    <li v-for="item in items" :key="item.categoryId ?? 'none'">
      <button type="button" class="flex w-full items-center gap-3 py-2.5 text-left outline-none rounded-md focus-visible:ring-3 focus-visible:ring-ring/50" @click="emit('select', item)">
        <CategoryIcon :icon="item.icon" :color="item.color" size="sm" />
        <span class="min-w-0 flex-1">
          <span class="flex items-baseline justify-between gap-3">
            <span class="truncate text-sm font-semibold">{{ item.name }}</span>
            <Money :value="item.total" class="text-sm font-semibold" />
          </span>
          <span class="mt-1.5 flex items-center gap-2">
            <span class="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
              <span class="block h-full rounded-full" :style="{ width: `${Math.max(2, item.percent)}%`, backgroundColor: item.color }" />
            </span>
            <span class="w-10 text-right text-[11px] tabular text-muted-foreground">{{ item.percent.toFixed(0) }}%</span>
          </span>
        </span>
      </button>
    </li>
  </ul>
</template>
