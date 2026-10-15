<script setup lang="ts">
import { computed } from 'vue'
import { iconFor } from '@/lib/icons'
import { cn } from '@/lib/utils'

const props = withDefaults(defineProps<{
  icon?: string | null
  color?: string | null
  size?: 'sm' | 'md' | 'lg'
  class?: string
}>(), { size: 'md' })

const Icon = computed(() => iconFor(props.icon))
const sizes = { sm: 'size-7 [&_svg]:size-3.5', md: 'size-10 [&_svg]:size-[18px]', lg: 'size-12 [&_svg]:size-5' }
const bg = computed(() => props.color ? `${props.color}22` : 'var(--muted)')
const fg = computed(() => props.color ?? 'var(--muted-foreground)')
</script>

<template>
  <span
    :class="cn('inline-flex shrink-0 items-center justify-center rounded-full', sizes[size], props.class)"
    :style="{ backgroundColor: bg, color: fg }"
    aria-hidden="true"
  >
    <component :is="Icon" :stroke-width="2.25" />
  </span>
</template>
