<script setup lang="ts">
import { computed, ref } from 'vue'
import type { MonthTotal } from '@/api/types'
import { money, monthShortLabel } from '@/lib/format'
import { cn } from '@/lib/utils'

const props = defineProps<{ history: MonthTotal[]; selected?: string }>()
const emit = defineEmits<{ select: [month: string] }>()

const W = 600
const H = 160
const PAD_TOP = 12
const PAD_BOTTOM = 26
const hovered = ref<number | null>(null)

const max = computed(() => Math.max(1, ...props.history.flatMap((m) => [m.income, m.expense])))
const groupWidth = computed(() => W / Math.max(1, props.history.length))
const barWidth = computed(() => Math.min(22, groupWidth.value * 0.28))
const plotHeight = H - PAD_TOP - PAD_BOTTOM

function barHeight(v: number) {
  return v <= 0 ? 0 : Math.max(3, (v / max.value) * plotHeight)
}

const active = computed(() => (hovered.value == null ? null : props.history[hovered.value] ?? null))
</script>

<template>
  <div class="space-y-2">
    <div class="flex items-center justify-between gap-3 text-xs">
      <div class="flex items-center gap-4">
        <span class="flex items-center gap-1.5"><span class="size-2.5 rounded-sm bg-income" />Receitas</span>
        <span class="flex items-center gap-1.5"><span class="size-2.5 rounded-sm bg-expense" />Despesas</span>
      </div>
      <span v-if="active" class="tabular text-muted-foreground">
        {{ monthShortLabel(active.month) }}: <span class="text-income">+{{ money(active.income) }}</span> / <span class="text-expense">−{{ money(active.expense) }}</span>
      </span>
    </div>
    <svg :viewBox="`0 0 ${W} ${H}`" class="h-40 w-full" role="img" aria-label="Receitas e despesas dos últimos meses" @mouseleave="hovered = null">
      <line :x1="0" :x2="W" :y1="H - PAD_BOTTOM" :y2="H - PAD_BOTTOM" class="stroke-border" stroke-width="1" />
      <g v-for="(m, i) in history" :key="m.month" class="cursor-pointer" @mouseenter="hovered = i" @click="emit('select', m.month)">
        <rect :x="i * groupWidth" :y="0" :width="groupWidth" :height="H" fill="transparent" />
        <rect
          v-if="hovered === i || selected === m.month"
          :x="i * groupWidth + 4" :y="PAD_TOP - 6" :width="groupWidth - 8" :height="plotHeight + 12"
          rx="8" class="fill-accent/70"
        />
        <rect
          :x="i * groupWidth + groupWidth / 2 - barWidth - 1"
          :y="H - PAD_BOTTOM - barHeight(m.income)"
          :width="barWidth" :height="barHeight(m.income)" rx="3"
          class="fill-income"
        />
        <rect
          :x="i * groupWidth + groupWidth / 2 + 1"
          :y="H - PAD_BOTTOM - barHeight(m.expense)"
          :width="barWidth" :height="barHeight(m.expense)" rx="3"
          class="fill-expense"
        />
        <text
          :x="i * groupWidth + groupWidth / 2" :y="H - 8" text-anchor="middle"
          :class="cn('text-[12px] font-semibold', selected === m.month ? 'fill-foreground' : 'fill-muted-foreground')"
        >
          {{ monthShortLabel(m.month) }}
        </text>
      </g>
    </svg>
  </div>
</template>
