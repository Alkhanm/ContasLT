<script setup lang="ts">
import { LoaderCircle, CloudOff } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { syncNow, syncState } from '@/data/session'

/**
 * Primeiro acesso neste aparelho: espera a primeira sincronização trazer os dados antes de mostrar as telas
 * (senão elas apareceriam vazias e os vencimentos recorrentes seriam gerados em dobro). Depois disso o app abre
 * direto nos dados locais, com ou sem rede.
 */
</script>

<template>
  <slot v-if="syncState.initialDone" />
  <div v-else class="flex flex-col items-center gap-4 py-24 text-center">
    <template v-if="syncState.status === 'offline' || syncState.status === 'error'">
      <CloudOff class="size-8 text-muted-foreground" />
      <div class="space-y-1">
        <p class="font-semibold">Não foi possível baixar seus dados</p>
        <p class="text-sm text-muted-foreground">{{ syncState.error ?? 'Sem conexão. Conecte-se para o primeiro acesso neste aparelho.' }}</p>
      </div>
      <Button variant="outline" @click="syncNow()">Tentar de novo</Button>
    </template>
    <template v-else>
      <LoaderCircle class="size-8 animate-spin text-muted-foreground" />
      <p class="text-sm text-muted-foreground">Baixando seus dados para este aparelho…</p>
    </template>
  </div>
</template>
