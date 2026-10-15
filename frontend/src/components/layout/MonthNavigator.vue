<script setup lang="ts">
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { usePeriodStore } from '@/stores/period'
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
      :class="cn('min-w-40 rounded-full px-3 py-1.5 text-center text-base font-bold tracking-tight outline-none focus-visible:ring-3 focus-visible:ring-ring/50 hover:bg-accent', !period.isCurrent && 'text-primary')"
      :title="period.isCurrent ? 'Mês atual' : 'Voltar ao mês atual'"
      @click="period.today()"
    >
      {{ period.label }}
    </button>
    <button type="button" class="inline-flex size-9 items-center justify-center rounded-full hover:bg-accent outline-none focus-visible:ring-3 focus-visible:ring-ring/50" aria-label="Próximo mês" @click="period.next()">
      <ChevronRight class="size-5" />
    </button>
  </div>
</template>
