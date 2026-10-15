<script setup lang="ts">
defineProps<{ title: string; hint?: string }>()
</script>

<template>
  <div class="mb-3 flex items-end justify-between gap-3">
    <div>
      <h2 class="text-base font-bold tracking-tight">{{ title }}</h2>
      <p v-if="hint" class="text-xs text-muted-foreground">{{ hint }}</p>
    </div>
    <div v-if="$slots.default" class="shrink-0">
      <slot />
    </div>
  </div>
</template>
