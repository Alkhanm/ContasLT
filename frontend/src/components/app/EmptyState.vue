<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  icon?: Component
  title: string
  description?: string
}>()
</script>

<template>
  <div class="flex flex-col items-center justify-center gap-2 px-6 py-12 text-center">
    <span v-if="icon" class="mb-1 inline-flex size-12 items-center justify-center rounded-full bg-muted text-muted-foreground">
      <component :is="icon" class="size-5" />
    </span>
    <p class="font-semibold">{{ title }}</p>
    <p v-if="description" class="max-w-xs text-sm text-muted-foreground">{{ description }}</p>
    <div v-if="$slots.default" class="mt-3">
      <slot />
    </div>
  </div>
</template>
