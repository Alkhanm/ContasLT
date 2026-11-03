<script setup lang="ts">
import { computed, ref } from 'vue'
import type { MonthValue } from '@/api/types'
import { money, monthShortLabel } from '@/lib/format'
import { cn } from '@/lib/utils'

/** Barras de um valor por mês (histórico de uma categoria ou de um item), na cor dada; o mês selecionado fica em destaque. */
const props = withDefaults(defineProps<{
  values: MonthValue[]
  color?: string
  selected?: string
  /** Altura em px da área do gráfico. */
  height?: number
}>(), { height: 72 })
const emit = defineEmits<{ select: [month: string] }>()

const hovered = ref<number | null>(null)
const W = 320
const PAD_BOTTOM = 18
const max = computed(() => Math.max(0.01, ...props.values.map((v) => v.total)))
const groupWidth = computed(() => W / Math.max(1, props.values.length))
const barWidth = computed(() => Math.min(28, groupWidth.value * 0.55))
const plot = computed(() => props.height - PAD_BOTTOM - 4)

function barHeight(v: number) {
  return v <= 0 ? 0 : Math.max(2, (v / max.value) * plot.value)
}
const active = computed(() => (hovered.value == null ? null : props.values[hovered.value] ?? null))
</script>

<template>
  <div>
    <p class="h-4 text-right text-[11px] tabular text-muted-foreground">
      <template v-if="active">{{ monthShortLabel(active.month) }}: <span class="font-semibold text-foreground">{{ money(active.total) }}</span></template>
    </p>
    <svg :viewBox="`0 0 ${W} ${height}`" :style="{ height: `${height}px` }" class="w-full" role="img" aria-label="Total por mês" @mouseleave="hovered = null">
      <line :x1="0" :x2="W" :y1="height - PAD_BOTTOM" :y2="height - PAD_BOTTOM" class="stroke-border" stroke-width="1" />
      <g v-for="(m, i) in values" :key="m.month" class="cursor-pointer" @mouseenter="hovered = i" @click="emit('select', m.month)">
        <rect :x="i * groupWidth" :y="0" :width="groupWidth" :height="height" fill="transparent" />
        <rect
          :x="i * groupWidth + (groupWidth - barWidth) / 2" :y="height - PAD_BOTTOM - barHeight(m.total)"
          :width="barWidth" :height="barHeight(m.total)" rx="3"
          :fill="color ?? 'var(--primary)'" :fill-opacity="selected && selected !== m.month && hovered !== i ? 0.45 : 1"
        />
        <text
          :x="i * groupWidth + groupWidth / 2" :y="height - 5" text-anchor="middle"
          :class="cn('text-[11px] font-semibold', selected === m.month ? 'fill-foreground' : 'fill-muted-foreground')"
        >
          {{ monthShortLabel(m.month) }}
        </text>
      </g>
    </svg>
  </div>
</template>
