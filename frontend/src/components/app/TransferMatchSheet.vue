<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowDown, ArrowLeftRight, Link2, X } from 'lucide-vue-next'
import { transfersApi } from '@/api'
import { keys, useTransferSuggestions } from '@/composables/queries'
import { queryClient } from '@/lib/queryClient'
import type { TransferSuggestion } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import EmptyState from './EmptyState.vue'
import FormSheet from './FormSheet.vue'
import Money from './Money.vue'
import { dateShort } from '@/lib/format'
import { notifyError, notifySuccess } from '@/lib/notify'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; linked: [] }>()

const items = ref<TransferSuggestion[]>([])
const busy = ref(new Set<string>())
const linkingAll = ref(false)

const { data, isPending: loading, error } = useTransferSuggestions(() => props.open)
const rejected = ref(0)
// Cópia local: cada par vinculado ou recusado some da lista na hora, antes do cache ser refeito.
watch(data, (d) => { if (d) { items.value = d.items; rejected.value = d.rejected } }, { immediate: true })
watch(error, (err) => { if (err) notifyError(err) })

/** "Não é transferência": some daqui e, entre duas contas recorrentes, de todos os meses. */
async function reject(s: TransferSuggestion) {
  busy.value.add(key(s))
  try {
    const r = await transfersApi.reject(s.from.id, s.to.id)
    items.value = items.value.filter((x) => key(x) !== key(s))
    rejected.value++
    void queryClient.invalidateQueries({ queryKey: keys.suggestions })
    notifySuccess(r.recurring
      ? 'Recusado. Essas duas contas recorrentes não serão mais sugeridas como transferência.'
      : 'Recusado. Esse par não será mais sugerido.')
  } catch (err) {
    notifyError(err)
  } finally {
    busy.value.delete(key(s))
  }
}

async function forgetRejections() {
  try {
    await transfersApi.forgetRejections()
    rejected.value = 0
    await queryClient.invalidateQueries({ queryKey: keys.suggestions })
    notifySuccess('Pares recusados voltam a ser sugeridos')
  } catch (err) {
    notifyError(err)
  }
}

const key = (s: TransferSuggestion) => s.from.id

async function link(s: TransferSuggestion, quiet = false) {
  busy.value.add(key(s))
  try {
    await transfersApi.link(s.from.id, s.to.id)
    items.value = items.value.filter((x) => key(x) !== key(s))
    if (!quiet) notifySuccess('Transferência vinculada')
    emit('linked')
  } catch (err) {
    if (!quiet) notifyError(err)
    throw err
  } finally {
    busy.value.delete(key(s))
  }
}

async function linkAll() {
  linkingAll.value = true
  const batch = [...items.value]
  let ok = 0
  for (const s of batch) {
    try {
      await link(s, true)
      ok++
    } catch {
      // segue para o próximo; o que falhou continua na lista
    }
  }
  linkingAll.value = false
  if (ok) notifySuccess(`${ok} ${ok === 1 ? 'transferência vinculada' : 'transferências vinculadas'}`)
  if (ok < batch.length) notifyError(new Error(`${batch.length - ok} não puderam ser vinculadas`))
}

const total = computed(() => items.value.reduce((acc, s) => acc + s.from.amount, 0))
</script>

<template>
  <FormSheet
    :open="open"
    title="Cruzar transferências"
    description="Saídas e entradas de mesmo valor em contas diferentes, com até 3 dias de diferença. Ao vincular, o par deixa de contar como despesa e receita."
    @update:open="emit('update:open', $event)"
  >
    <div v-if="loading" class="space-y-3">
      <Skeleton v-for="i in 3" :key="i" class="h-28 w-full rounded-xl" />
    </div>

    <ul v-else-if="items.length" class="space-y-3">
      <li v-for="s in items" :key="key(s)" class="rounded-xl border bg-card p-3">
        <div class="flex items-start justify-between gap-3">
          <div class="min-w-0 flex-1 space-y-1.5 text-sm">
            <p class="flex items-baseline gap-2">
              <span class="shrink-0 rounded bg-expense/10 px-1.5 text-[11px] font-bold text-expense">{{ s.from.accountName }}</span>
              <span class="truncate text-muted-foreground">{{ s.from.description }}</span>
              <span class="ml-auto shrink-0 text-xs text-muted-foreground">{{ dateShort(s.from.dueDate) }}</span>
            </p>
            <p class="pl-1 text-muted-foreground"><ArrowDown class="size-3.5" /></p>
            <p class="flex items-baseline gap-2">
              <span class="shrink-0 rounded bg-income/10 px-1.5 text-[11px] font-bold text-income">{{ s.to.accountName }}</span>
              <span class="truncate text-muted-foreground">{{ s.to.description }}</span>
              <span class="ml-auto shrink-0 text-xs text-muted-foreground">{{ dateShort(s.to.dueDate) }}</span>
            </p>
          </div>
        </div>
        <div class="mt-3 flex items-center justify-between gap-3 border-t pt-3">
          <span class="text-xs text-muted-foreground">
            <Money :value="s.from.amount" class="text-sm font-semibold text-foreground" />
            <template v-if="s.daysApart"> · {{ s.daysApart }} {{ s.daysApart === 1 ? 'dia de diferença' : 'dias de diferença' }}</template>
            <template v-else> · mesmo dia</template>
          </span>
          <span class="flex items-center gap-1.5">
            <Button size="sm" variant="ghost" class="text-muted-foreground" :disabled="busy.has(key(s)) || linkingAll" title="Não é transferência" @click="reject(s)">
              <X /> Não é
            </Button>
            <Button size="sm" variant="outline" :disabled="busy.has(key(s)) || linkingAll" @click="link(s)">
              <Link2 /> Vincular
            </Button>
          </span>
        </div>
      </li>
    </ul>

    <div v-else class="rounded-2xl border border-dashed">
      <EmptyState :icon="ArrowLeftRight" title="Nenhum par encontrado" description="Quando uma saída e uma entrada de mesmo valor aparecerem em contas diferentes, elas serão sugeridas aqui. Você também pode criar uma transferência pelo botão +." />
    </div>

    <p v-if="!loading && rejected" class="mt-3 text-center text-xs text-muted-foreground">
      {{ rejected }} {{ rejected === 1 ? 'par recusado não aparece' : 'pares recusados não aparecem' }} aqui.
      <button type="button" class="font-semibold text-primary underline-offset-2 hover:underline" @click="forgetRejections">Voltar a sugerir</button>
    </p>

    <template v-if="items.length" #footer>
      <Button class="h-11 w-full text-base font-semibold" :disabled="linkingAll" @click="linkAll">
        {{ linkingAll ? 'Vinculando…' : `Vincular ${items.length === 1 ? 'esta' : `todas as ${items.length}`}` }}
        <span v-if="items.length > 1" class="font-normal opacity-80">· <Money :value="total" /></span>
      </Button>
    </template>
  </FormSheet>
</template>
