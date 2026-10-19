<script setup lang="ts">
import {
  AlertDialog, AlertDialogCancel, AlertDialogContent, AlertDialogDescription,
  AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'

withDefaults(defineProps<{
  open: boolean
  title: string
  description?: string
  confirmLabel?: string
  destructive?: boolean
}>(), { confirmLabel: 'Confirmar', destructive: false })

const emit = defineEmits<{ 'update:open': [value: boolean]; confirm: [] }>()

// O AlertDialogAction do reka-ui fecha o diálogo antes de disparar o nosso click,
// o que zerava o item selecionado no pai antes da confirmação. Aqui a ordem é explícita:
// primeiro confirma, depois fecha.
function onConfirm() {
  emit('confirm')
  emit('update:open', false)
}
</script>

<template>
  <AlertDialog :open="open" @update:open="emit('update:open', $event)">
    <AlertDialogContent class="max-w-sm">
      <AlertDialogHeader>
        <AlertDialogTitle>{{ title }}</AlertDialogTitle>
        <AlertDialogDescription v-if="description">{{ description }}</AlertDialogDescription>
      </AlertDialogHeader>
      <AlertDialogFooter>
        <AlertDialogCancel>Cancelar</AlertDialogCancel>
        <Button :variant="destructive ? 'destructive' : 'default'" @click="onConfirm">
          {{ confirmLabel }}
        </Button>
      </AlertDialogFooter>
    </AlertDialogContent>
  </AlertDialog>
</template>
