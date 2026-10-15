<script setup lang="ts">
import { Label } from '@/components/ui/label'

defineProps<{ label: string; for?: string; error?: string; hint?: string }>()
</script>

<template>
  <div class="space-y-1.5">
    <Label :for="$props.for" class="text-xs font-semibold text-muted-foreground">{{ label }}</Label>
    <slot />
    <p v-if="error" class="text-xs text-destructive">{{ error }}</p>
    <p v-else-if="hint" class="text-xs text-muted-foreground">{{ hint }}</p>
  </div>
</template>
