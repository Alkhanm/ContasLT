<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Inbox, Repeat, TriangleAlert } from 'lucide-vue-next'
import { inboxApi } from '@/api'
import { useInbox } from '@/composables/queries'
import type { InboxSuggestion } from '@/services/inbox'
import CategoryIcon from '@/components/app/CategoryIcon.vue'
import EmptyState from '@/components/app/EmptyState.vue'
import Money from '@/components/app/Money.vue'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { dateShort, money } from '@/lib/format'
import { notifyError, notifySuccess } from '@/lib/notify'
import { useUiStore } from '@/stores/ui'

/**
 * Caixa "A confirmar": compras, Pix e pagamentos que o app Android leu das notificações dos bancos, já com conta,
 * categoria e vencimento recorrente sugeridos. O que for confirmado ou descartado aqui some do celular.
 */
const ui = useUiStore()
const { data, isPending: loading, error } = useInbox()
const items = computed(() => data.value ?? [])
watch(error, (err) => { if (err) notifyError(err) })
const busy = ref(new Set<string>())
const expanded = ref(new Set<string>())

async function run(id: string, fn: () => Promise<unknown>, message: string) {
  busy.value = new Set(busy.value).add(id)
  try {
    await fn()
    notifySuccess(message)
    ui.dataChanged()
  } catch (err) {
    notifyError(err)
  } finally {
    const next = new Set(busy.value)
    next.delete(id)
    busy.value = next
  }
}

function confirm(s: InboxSuggestion) {
  void run(s.id, () => inboxApi.confirm(s.id), s.occurrenceId ? 'Vencimento pago' : 'Lançamento adicionado')
}

function discard(s: InboxSuggestion) {
  void run(s.id, () => inboxApi.discard(s.id), 'Descartado')
}

function edit(s: InboxSuggestion) {
  ui.newTransaction({
    type: s.input.type,
    prefill: {
      input: s.input,
      settleOccurrenceId: s.occurrenceId,
      onSaved: (tx) => inboxApi.markConfirmed(s.id, tx.id),
    },
  })
}

function toggleRaw(id: string) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

/** "agora", "há 5 min", "há 2 h", "ontem", "08 nov". */
function ago(at: number) {
  const min = Math.floor((Date.now() - at) / 60_000)
  if (min < 1) return 'agora'
  if (min < 60) return `há ${min} min`
  if (min < 24 * 60) return `há ${Math.floor(min / 60)} h`
  const d = new Date(at)
  const y = new Date()
  y.setDate(y.getDate() - 1)
  if (d.toDateString() === y.toDateString()) return 'ontem'
  const iso = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return dateShort(iso)
}

function time(at: number) {
  const d = new Date(at)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm text-muted-foreground">
      Movimentos lidos das notificações dos bancos pelo app ContasLT no celular. Confirme aqui ou lá: o que for resolvido em um some do outro.
    </p>

    <div v-if="loading" class="space-y-2">
      <Skeleton v-for="i in 3" :key="i" class="h-28 w-full rounded-xl" />
    </div>

    <ul v-else-if="items.length" class="space-y-3">
      <li v-for="s in items" :key="s.id" class="space-y-3 rounded-2xl border bg-card p-4">
        <p class="text-xs font-semibold text-muted-foreground">{{ s.source }} · {{ ago(s.postedAt) }} às {{ time(s.postedAt) }}</p>
        <div class="flex items-center gap-3">
          <CategoryIcon :icon="s.categoryIcon" :color="s.categoryColor" />
          <span class="min-w-0 flex-1">
            <span class="block truncate font-semibold">{{ s.input.description }}</span>
            <span class="block truncate text-xs text-muted-foreground">
              {{ dateShort(s.input.dueDate) }} · <span :class="!s.accountName && 'font-semibold text-destructive'">{{ s.accountName ?? 'Escolha a conta' }}</span><template v-if="s.categoryName"> · {{ s.categoryName }}</template>
            </span>
          </span>
          <Money :value="s.input.amount" :type="s.input.type" signed class="font-bold" />
        </div>

        <p v-if="s.occurrenceId" class="flex items-start gap-2 rounded-lg bg-primary/5 px-3 py-2 text-xs">
          <Repeat class="mt-0.5 size-3.5 shrink-0 text-primary" />
          <span>
            Parece o pagamento de <strong>{{ s.occurrenceDescription }}</strong> (vence {{ s.occurrenceDate ? dateShort(s.occurrenceDate) : '' }}, previsto {{ money(s.occurrenceAmount) }}). Confirmar paga esse vencimento.
          </span>
        </p>
        <p v-if="s.duplicateOfId" class="flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/10 px-3 py-2 text-xs" role="alert">
          <TriangleAlert class="mt-0.5 size-3.5 shrink-0" />
          <span>Você já lançou <strong>{{ s.duplicateOfDescription }}</strong> em {{ s.duplicateOfDate ? dateShort(s.duplicateOfDate) : '' }} com o mesmo valor. Confira antes de confirmar.</span>
        </p>

        <button
          type="button"
          :class="['block w-full rounded-md text-left text-xs whitespace-pre-line text-muted-foreground', !expanded.has(s.id) && 'line-clamp-2']"
          :aria-expanded="expanded.has(s.id)"
          title="Texto da notificação"
          @click="toggleRaw(s.id)"
        >{{ s.raw }}</button>

        <div class="flex flex-wrap justify-end gap-2">
          <Button variant="ghost" size="sm" :disabled="busy.has(s.id)" @click="discard(s)">Descartar</Button>
          <Button variant="outline" size="sm" :disabled="busy.has(s.id)" @click="edit(s)">Editar</Button>
          <Button size="sm" :disabled="busy.has(s.id) || !s.input.accountId" @click="confirm(s)">Confirmar</Button>
        </div>
      </li>
    </ul>

    <div v-else class="rounded-2xl border border-dashed">
      <EmptyState
        :icon="Inbox" title="Nada a confirmar"
        description="As compras, Pix e pagamentos que os apps dos bancos notificarem no celular (com o app ContasLT para Android) aparecem aqui, já preenchidos."
      />
    </div>
  </div>
</template>
