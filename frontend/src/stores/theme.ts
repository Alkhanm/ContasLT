import { computed, ref, watchEffect } from 'vue'
import { defineStore } from 'pinia'
import { useMediaQuery } from '@vueuse/core'

export type ThemePreference = 'light' | 'dark' | 'system'
const KEY = 'contaslt.theme'

function readPreference(): ThemePreference {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'light' || v === 'dark' ? v : 'system'
  } catch {
    return 'system'
  }
}

export const useThemeStore = defineStore('theme', () => {
  const preference = ref<ThemePreference>(readPreference())
  const prefersDark = useMediaQuery('(prefers-color-scheme: dark)')
  const isDark = computed(() => preference.value === 'dark' || (preference.value === 'system' && prefersDark.value))

  function set(value: ThemePreference) {
    preference.value = value
    try {
      localStorage.setItem(KEY, value)
    } catch {
      // sem persistência disponível
    }
  }

  watchEffect(() => {
    document.documentElement.classList.toggle('dark', isDark.value)
    const meta = document.querySelector('meta[name="theme-color"]:not([media])')
    const color = isDark.value ? '#0f1f18' : '#f3f6f4'
    if (meta) meta.setAttribute('content', color)
    else {
      const m = document.createElement('meta')
      m.name = 'theme-color'
      m.content = color
      document.head.appendChild(m)
    }
  })

  return { preference, isDark, set }
})
