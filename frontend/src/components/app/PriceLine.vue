<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ItemPurchase } from '@/api/types'
import { dateShort, money, moneyPlain } from '@/lib/format'
import { cn } from '@/lib/utils'

/** Linha do preço unitário de um item ao longo das compras. Compacta (sparkline) ou com eixo de datas e valores. */
const props = withDefaults(defineProps<{
  purchases: ItemPurchase[]
  color?: string
  detailed?: boolean
}>(), { detailed: false })

const W = computed(() => (props.detailed ? 320 : 96))
const H = computed(() => (props.detailed ? 110 : 28))
const padX = computed(() => (props.detailed ? 8 : 2))
const padTop = computed(() => (props.detailed ? 16 : 3))
const padBottom = computed(() => (props.detailed ? 24 : 3))
/** Com poucas compras, o valor fica escrito em cada ponto (no celular não há hover). */
const LABEL_EVERY_POINT_UP_TO = 8
const hovered = ref<number | null>(null)

const prices = computed(() => props.purchases.map((p) => p.unitPrice ?? p.total))
const min = computed(() => Math.min(...prices.value))
const max = computed(() => Math.max(...prices.value))
const points = computed(() => {
  const n = prices.value.length
  const span = max.value - min.value
  const plot = H.value - padTop.value - padBottom.value
  return prices.value.map((v, i) => ({
    x: n === 1 ? W.value / 2 : padX.value + (i / (n - 1)) * (W.value - padX.value * 2),
    y: span === 0 ? padTop.value + plot / 2 : padTop.value + plot - ((v - min.value) / span) * plot,
    value: v,
  }))
})
const path = computed(() => points.value.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' '))
const labelled = computed(() => props.detailed && points.value.length <= LABEL_EVERY_POINT_UP_TO)
const active = computed(() => (hovered.value == null || labelled.value ? null : { point: points.value[hovered.value]!, purchase: props.purchases[hovered.value]! }))
const stroke = computed(() => props.color ?? 'var(--primary)')
</script>

<template>
  <svg
    :viewBox="`0 0 ${W} ${H}`" :class="detailed ? 'h-[110px] w-full' : 'h-7 w-24'" role="img"
    :aria-label="`Preço unitário: de ${money(prices[0])} a ${money(prices[prices.length - 1])}`"
    @mouseleave="hovered = null"
  >
    <line v-if="detailed" :x1="padX" :x2="W - padX" :y1="H - padBottom + 6" :y2="H - padBottom + 6" class="stroke-border" stroke-width="1" />
    <path :d="path" fill="none" :stroke="stroke" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" />
    <g v-for="(p, i) in points" :key="i">
      <circle :cx="p.x" :cy="p.y" :r="detailed ? 3.5 : 2" :fill="stroke" />
      <text
        v-if="labelled" :x="Math.min(W - 24, Math.max(24, p.x))" :y="p.y - 7" text-anchor="middle"
        :class="cn('text-[10px] tabular', i === points.length - 1 ? 'fill-foreground font-semibold' : 'fill-muted-foreground')"
      >
        {{ moneyPlain(p.value) }}
      </text>
      <rect
        v-if="detailed" :x="p.x - (W / Math.max(1, points.length)) / 2" :y="0" :width="W / Math.max(1, points.length)" :height="H"
        fill="transparent" class="cursor-default" @mouseenter="hovered = i"
      />
    </g>
    <template v-if="detailed">
      <text v-if="points.length > 1" :x="padX" :y="H - 4" class="fill-muted-foreground text-[10px]">{{ dateShort(purchases[0]!.date) }}</text>
      <text :x="W - padX" :y="H - 4" text-anchor="end" class="fill-muted-foreground text-[10px]">{{ dateShort(purchases[purchases.length - 1]!.date) }}</text>
      <g v-if="active">
        <circle :cx="active.point.x" :cy="active.point.y" r="5.5" fill="none" :stroke="stroke" stroke-width="1.5" />
        <text
          :x="Math.min(W - 60, Math.max(60, active.point.x))" :y="active.point.y < padTop + 20 ? active.point.y + 18 : active.point.y - 10"
          text-anchor="middle" class="fill-foreground text-[11px] font-semibold tabular"
        >
          {{ money(active.point.value) }} · {{ dateShort(active.purchase.date) }}
        </text>
      </g>
    </template>
  </svg>
</template>
