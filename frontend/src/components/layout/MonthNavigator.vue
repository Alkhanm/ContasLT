<script setup lang="ts">
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { usePeriodStore } from '@/stores/period'
import { monthShortLabel } from '@/lib/format'
import { cn } from '@/lib/utils'

const period = usePeriodStore()
</script>

<template>
  <div class="flex items-center justify-center gap-1">
    <button type="button" class="inline-flex size-9 items-center justify-center rounded-full hover:bg-accent outline-none focus-visible:ring-3 focus-visible:ring-ring/50" aria-label="Mês anterior" @click="period.previous()">
      <ChevronLeft class="size-5" />
    </button>
    <button
      type="button"
      :class="cn('whitespace-nowrap rounded-full px-2 py-1.5 text-center text-base font-bold tracking-tight outline-none focus-visible:ring-3 focus-visible:ring-ring/50 hover:bg-accent sm:min-w-40 sm:px-3', !period.isCurrent && 'text-primary')"
      :title="period.isCurrent ? 'Mês atual' : 'Voltar ao mês atual'"
      @click="period.today()"
    >
      <span class="sm:hidden">{{ monthShortLabel(period.month) }} {{ period.month.slice(0, 4) }}</span>
      <span class="hidden sm:inline">{{ period.label }}</span>
    </button>
    <button type="button" class="inline-flex size-9 items-center justify-center rounded-full hover:bg-accent outline-none focus-visible:ring-3 focus-visible:ring-ring/50" aria-label="Próximo mês" @click="period.next()">
      <ChevronRight class="size-5" />
    </button>
  </div>
</template>
