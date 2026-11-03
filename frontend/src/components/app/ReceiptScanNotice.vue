<script setup lang="ts">
import { Clock, TriangleAlert } from 'lucide-vue-next'
import type { ReceiptScanAction, Transaction } from '@/api/types'
import { Button } from '@/components/ui/button'
import { dateShort, money } from '@/lib/format'

/**
 * Aviso de leitura adiada do comprovante (worker no computador): aguardando, divergente (o cupom diz outro
 * total ou outra data) ou falhou. Mesmo estilo do aviso de duplicata e das transferências a vincular.
 */
const props = defineProps<{
  transaction: Pick<Transaction, 'amount' | 'dueDate' | 'receiptScanStatus' | 'receiptScanTotal' | 'receiptScanDate'>
  busy?: boolean
}>()

const emit = defineEmits<{ action: [action: ReceiptScanAction] }>()

function scannedSummary() {
  const t = props.transaction
  const parts: string[] = []
  if (t.receiptScanTotal != null) parts.push(`total ${money(t.receiptScanTotal)}`)
  if (t.receiptScanDate) parts.push(`data ${dateShort(t.receiptScanDate)}`)
  return parts.join(' e ')
}

function enteredSummary() {
  const t = props.transaction
  const parts: string[] = []
  if (t.receiptScanTotal != null) parts.push(money(t.amount))
  if (t.receiptScanDate) parts.push(dateShort(t.dueDate))
  return parts.join(' em ')
}
</script>

<template>
  <p v-if="transaction.receiptScanStatus === 'PENDING'" class="flex items-start gap-2 rounded-lg bg-muted/60 px-3 py-2 text-xs text-muted-foreground">
    <Clock class="mt-0.5 size-3.5 shrink-0" />
    <span>Comprovante aguardando leitura. Os itens entram sozinhos quando o computador com o leitor estiver ligado.</span>
  </p>

  <div v-else-if="transaction.receiptScanStatus === 'DIVERGENT'" class="space-y-2 rounded-lg border border-warning/40 bg-warning/10 px-3 py-2 text-xs" role="alert">
    <p class="flex items-start gap-2">
      <TriangleAlert class="mt-0.5 size-3.5 shrink-0 text-warning" />
      <span class="flex-1">
        <strong>Comprovante lido com diferença.</strong> O cupom diz {{ scannedSummary() }}; você lançou {{ enteredSummary() }}.
      </span>
    </p>
    <span class="flex flex-wrap gap-2 pl-5">
      <Button type="button" size="sm" variant="outline" class="h-7" :disabled="busy" @click="emit('action', 'ACCEPT')">Usar o do comprovante</Button>
      <Button type="button" size="sm" variant="ghost" class="h-7" :disabled="busy" @click="emit('action', 'DISMISS')">Manter o lançado</Button>
    </span>
  </div>

  <div v-else-if="transaction.receiptScanStatus === 'FAILED'" class="space-y-2 rounded-lg border border-warning/40 bg-warning/10 px-3 py-2 text-xs" role="alert">
    <p class="flex items-start gap-2">
      <TriangleAlert class="mt-0.5 size-3.5 shrink-0 text-warning" />
      <span class="flex-1"><strong>Não foi possível ler o comprovante.</strong> A foto continua anexada; tente de novo com o leitor ligado.</span>
    </p>
    <span class="flex flex-wrap gap-2 pl-5">
      <Button type="button" size="sm" variant="outline" class="h-7" :disabled="busy" @click="emit('action', 'RETRY')">Tentar de novo</Button>
      <Button type="button" size="sm" variant="ghost" class="h-7" :disabled="busy" @click="emit('action', 'DISMISS')">Deixar assim</Button>
    </span>
  </div>
</template>
