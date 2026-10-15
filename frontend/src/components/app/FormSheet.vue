<script setup lang="ts">
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { useIsDesktop } from '@/composables/useIsDesktop'
import { cn } from '@/lib/utils'

defineProps<{ open: boolean; title: string; description?: string }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()
const isDesktop = useIsDesktop()
</script>

<template>
  <Sheet :open="open" @update:open="emit('update:open', $event)">
    <SheetContent
      :side="isDesktop ? 'right' : 'bottom'"
      :class="cn('flex flex-col gap-0 p-0',
        isDesktop ? 'w-full sm:max-w-md' : 'max-h-[92dvh] rounded-t-2xl')"
    >
      <SheetHeader class="shrink-0 px-5 pt-5 pb-3 text-left">
        <SheetTitle class="text-lg font-bold">{{ title }}</SheetTitle>
        <SheetDescription :class="!description && 'sr-only'">{{ description ?? title }}</SheetDescription>
      </SheetHeader>
      <div class="min-h-0 flex-1 overflow-y-auto px-5 pb-5">
        <slot />
      </div>
      <div v-if="$slots.footer" class="shrink-0 border-t bg-background px-5 py-3 safe-bottom">
        <slot name="footer" />
      </div>
    </SheetContent>
  </Sheet>
</template>
