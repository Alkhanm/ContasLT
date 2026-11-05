<script setup lang="ts">
import { computed } from 'vue'
import { CloudAlert, CloudCheck, CloudOff, CloudUpload, RefreshCw } from 'lucide-vue-next'
import { syncNow, syncState } from '@/data/session'

/** Estado da sincronização com o servidor: os dados ficam no aparelho, isto só diz se já subiram/desceram. */
const view = computed(() => {
  if (syncState.status === 'syncing') return { icon: RefreshCw, spin: true, label: 'Sincronizando…', tone: 'text-muted-foreground' }
  if (syncState.status === 'offline') {
    return { icon: CloudOff, spin: false, label: syncState.pending ? `Offline · ${syncState.pending} para enviar` : 'Offline', tone: 'text-muted-foreground' }
  }
  if (syncState.status === 'error') return { icon: CloudAlert, spin: false, label: syncState.error ?? 'Falha ao sincronizar', tone: 'text-destructive' }
  if (syncState.pending) return { icon: CloudUpload, spin: false, label: `${syncState.pending} para enviar`, tone: 'text-muted-foreground' }
  return { icon: CloudCheck, spin: false, label: 'Sincronizado', tone: 'text-muted-foreground' }
})
</script>

<template>
  <button
    type="button"
    :class="['inline-flex size-9 items-center justify-center rounded-md hover:bg-accent', view.tone]"
    :title="view.label"
    :aria-label="view.label"
    @click="syncNow()"
  >
    <component :is="view.icon" :class="['size-4', view.spin && 'animate-spin']" />
  </button>
</template>
