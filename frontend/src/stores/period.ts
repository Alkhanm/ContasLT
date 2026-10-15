import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { currentMonth, monthLabel, shiftMonth } from '@/lib/format'

/** Mês selecionado, compartilhado entre início e lançamentos. */
export const usePeriodStore = defineStore('period', () => {
  const month = ref(currentMonth())
  const label = computed(() => monthLabel(month.value))
  const isCurrent = computed(() => month.value === currentMonth())

  function previous() {
    month.value = shiftMonth(month.value, -1)
  }
  function next() {
    month.value = shiftMonth(month.value, 1)
  }
  function today() {
    month.value = currentMonth()
  }

  return { month, label, isCurrent, previous, next, today }
})
