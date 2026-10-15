<script setup lang="ts">
import { RouterView } from 'vue-router'
import { Toaster } from '@/components/ui/sonner'
import { useThemeStore } from '@/stores/theme'

const theme = useThemeStore()
</script>

<template>
  <RouterView />
  <Toaster :theme="theme.isDark ? 'dark' : 'light'" position="top-center" :duration="3500" close-button />
</template>
