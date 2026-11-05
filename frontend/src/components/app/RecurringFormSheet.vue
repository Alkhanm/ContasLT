<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Trash2 } from 'lucide-vue-next'
import { recurringApi } from '@/api'
import type { Frequency, RecurringBill, RecurringBillInput, Transaction, TransactionType } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import AccountSelect from './AccountSelect.vue'
import CategorySelect from './CategorySelect.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import DateInput from './DateInput.vue'
import FormField from './FormField.vue'
import FormSheet from './FormSheet.vue'
import MoneyInput from './MoneyInput.vue'
import TypeToggle from './TypeToggle.vue'
import { dateShort, frequencyLabels, todayIso } from '@/lib/format'
import { MATCH_LEVEL_DEFAULT, matchLevelHint } from '@/lib/matching'
import { fieldErrors, notifyError, notifySuccess } from '@/lib/notify'
import { cn } from '@/lib/utils'
import { useCatalogStore } from '@/stores/catalog'

const props = defineProps<{
  open: boolean
  bill: RecurringBill | null
  /** Lançamento existente que vira a primeira ocorrência: preenche o formulário e é vinculado ao salvar. */
  fromTransaction?: Transaction | null
}>()
const emit = defineEmits<{ 'update:open': [value: boolean]; saved: [] }>()

const catalog = useCatalogStore()
type EndMode = 'never' | 'installments' | 'date'

const form = reactive({
  type: 'EXPENSE' as TransactionType,
  amount: null as number | null,
  description: '',
  frequency: 'MONTHLY' as Frequency,
  startDate: todayIso(),
  endMode: 'never' as EndMode,
  occurrences: 12 as number | undefined,
  endDate: '',
  categoryId: null as string | null,
  accountId: null as string | null,
  notes: '',
  active: true,
  matchStrictness: MATCH_LEVEL_DEFAULT,
})
const errors = ref<Record<string, string>>({})
const saving = ref(false)
const confirmDelete = ref(false)

const frequencies = Object.entries(frequencyLabels) as [Frequency, string][]
const endModes: { value: EndMode; label: string }[] = [
  { value: 'never', label: 'Sem fim' },
  { value: 'installments', label: 'Parcelado' },
  { value: 'date', label: 'Até uma data' },
]
const title = computed(() => (props.bill ? 'Editar conta recorrente' : props.fromTransaction ? 'Tornar recorrente' : 'Nova conta recorrente'))

watch(() => props.open, async (open) => {
  if (!open) return
  await catalog.load()
  errors.value = {}
  const b = props.bill
  const t = props.fromTransaction
  form.type = b?.type ?? t?.type ?? 'EXPENSE'
  form.amount = b?.amount ?? t?.amount ?? null
  form.description = b?.description ?? t?.description ?? ''
  form.frequency = b?.frequency ?? 'MONTHLY'
  form.startDate = b?.startDate ?? t?.dueDate ?? todayIso()
  form.endMode = b?.occurrences ? 'installments' : b?.endDate ? 'date' : 'never'
  form.occurrences = b?.occurrences ?? 12
  form.endDate = b?.endDate ?? ''
  form.categoryId = b?.categoryId ?? t?.categoryId ?? null
  form.accountId = b?.accountId ?? t?.accountId ?? catalog.activeAccounts[0]?.id ?? null
  form.notes = b?.notes ?? ''
  form.active = b?.active ?? true
  form.matchStrictness = b?.matchStrictness ?? MATCH_LEVEL_DEFAULT
})

watch(() => form.type, (type) => {
  const c = catalog.category(form.categoryId)
  if (c && c.type !== type) form.categoryId = null
})

function validate(): boolean {
  const e: Record<string, string> = {}
  if (!form.amount || form.amount <= 0) e.amount = 'Informe um valor maior que zero'
  if (!form.description.trim()) e.description = 'Informe a descrição'
  if (!form.startDate) e.startDate = 'Informe o primeiro vencimento'
  if (!form.accountId) e.accountId = 'Escolha a conta'
  if (form.endMode === 'installments' && (!form.occurrences || form.occurrences < 2)) e.occurrences = 'Pelo menos 2 parcelas'
  if (form.endMode === 'date' && !form.endDate) e.endDate = 'Informe a data final'
  if (form.endMode === 'date' && form.endDate && form.endDate < form.startDate) e.endDate = 'Deve ser depois do primeiro vencimento'
  errors.value = e
  return Object.keys(e).length === 0
}

async function save() {
  if (!validate()) return
  saving.value = true
  const input: RecurringBillInput = {
    description: form.description.trim(),
    amount: form.amount!,
    type: form.type,
    frequency: form.frequency,
    startDate: form.startDate,
    endDate: form.endMode === 'date' ? form.endDate : null,
    occurrences: form.endMode === 'installments' ? form.occurrences ?? null : null,
    accountId: form.accountId!,
    categoryId: form.categoryId,
    notes: form.notes.trim() || null,
    active: form.active,
    matchStrictness: form.matchStrictness,
    fromTransactionId: props.bill ? null : props.fromTransaction?.id ?? null,
  }
  try {
    if (props.bill) {
      await recurringApi.update(props.bill.id, input)
      notifySuccess('Conta recorrente atualizada')
    } else {
      await recurringApi.create(input)
      notifySuccess('Conta recorrente criada')
    }
    emit('update:open', false)
    emit('saved')
  } catch (err) {
    errors.value = fieldErrors(err)
    notifyError(err)
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!props.bill) return
  try {
    await recurringApi.remove(props.bill.id)
    notifySuccess('Conta recorrente excluída')
    confirmDelete.value = false
    emit('update:open', false)
    emit('saved')
  } catch (err) {
    notifyError(err)
  }
}
</script>

<template>
  <FormSheet :open="open" :title="title" @update:open="emit('update:open', $event)">
    <form id="recurring-form" class="space-y-4" @submit.prevent="save">
      <TypeToggle v-model="form.type" :disabled="!!fromTransaction" />
      <p v-if="fromTransaction" class="rounded-lg bg-muted px-3 py-2 text-xs text-muted-foreground">
        O lançamento de {{ dateShort(fromTransaction.dueDate) }} vira a primeira ocorrência: nada é duplicado e os próximos vencimentos são gerados a partir dele.
      </p>

      <FormField label="Valor" for="rb-amount" :error="errors.amount">
        <MoneyInput id="rb-amount" v-model="form.amount" :invalid="!!errors.amount" :autofocus="!bill" />
      </FormField>

      <FormField label="Descrição" for="rb-description" :error="errors.description">
        <Input id="rb-description" v-model="form.description" placeholder="Ex.: Aluguel" maxlength="140" autocomplete="off" />
      </FormField>

      <div class="grid grid-cols-2 gap-3">
        <FormField label="Repete" for="rb-frequency">
          <Select v-model="form.frequency">
            <SelectTrigger id="rb-frequency" class="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem v-for="[value, label] in frequencies" :key="value" :value="value">{{ label }}</SelectItem>
            </SelectContent>
          </Select>
        </FormField>
        <FormField label="Primeiro vencimento" for="rb-start" :error="errors.startDate">
          <DateInput id="rb-start" v-model="form.startDate" />
        </FormField>
      </div>

      <FormField label="Duração">
        <div role="radiogroup" class="grid grid-cols-3 gap-1 rounded-lg bg-muted p-1">
          <button
            v-for="m in endModes"
            :key="m.value"
            type="button"
            role="radio"
            :aria-checked="form.endMode === m.value"
            :class="cn('h-8 rounded-md text-xs font-semibold transition-colors outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
              form.endMode === m.value ? 'bg-card text-foreground shadow-xs' : 'text-muted-foreground hover:text-foreground')"
            @click="form.endMode = m.value"
          >
            {{ m.label }}
          </button>
        </div>
      </FormField>

      <FormField v-if="form.endMode === 'installments'" label="Número de parcelas" for="rb-occurrences" :error="errors.occurrences">
        <Input id="rb-occurrences" v-model.number="form.occurrences" type="number" min="2" max="600" inputmode="numeric" />
      </FormField>
      <FormField v-if="form.endMode === 'date'" label="Último vencimento" for="rb-end" :error="errors.endDate">
        <DateInput id="rb-end" v-model="form.endDate" />
      </FormField>

      <div class="grid grid-cols-2 gap-3">
        <FormField label="Conta" for="rb-account" :error="errors.accountId">
          <AccountSelect id="rb-account" v-model="form.accountId" />
        </FormField>
        <FormField label="Categoria" for="rb-category">
          <CategorySelect id="rb-category" v-model="form.categoryId" :type="form.type" />
        </FormField>
      </div>

      <label v-if="bill" class="flex items-center justify-between rounded-lg border px-4 py-3">
        <span>
          <span class="block text-sm font-semibold">Ativa</span>
          <span class="block text-xs text-muted-foreground">Desativar remove os vencimentos ainda não pagos.</span>
        </span>
        <Switch v-model="form.active" />
      </label>

      <FormField label="Precisão para casar com extrato e comprovante" for="rb-match" :hint="matchLevelHint(form.matchStrictness)">
        <div class="flex items-center gap-3">
          <input
            id="rb-match"
            v-model.number="form.matchStrictness"
            type="range"
            min="1"
            max="10"
            step="1"
            class="h-2 w-full cursor-pointer accent-primary"
          />
          <span class="w-12 shrink-0 text-right text-sm font-semibold tabular">{{ form.matchStrictness }}/10</span>
        </div>
        <div class="flex justify-between text-[11px] text-muted-foreground">
          <span>Só o nome</span>
          <span>Tudo exato</span>
        </div>
      </FormField>

      <FormField label="Observações" for="rb-notes">
        <Textarea id="rb-notes" v-model="form.notes" rows="2" maxlength="500" placeholder="Opcional" />
      </FormField>
    </form>

    <template #footer>
      <div class="flex items-center gap-2">
        <Button v-if="bill" type="button" variant="outline" size="icon" class="text-destructive" title="Excluir" aria-label="Excluir" @click="confirmDelete = true">
          <Trash2 />
        </Button>
        <Button type="submit" form="recurring-form" class="h-11 flex-1 text-base font-semibold" :disabled="saving">
          {{ saving ? 'Salvando…' : bill ? 'Salvar alterações' : 'Criar recorrência' }}
        </Button>
      </div>
    </template>
  </FormSheet>

  <ConfirmDialog
    v-model:open="confirmDelete"
    title="Excluir conta recorrente?"
    description="Os vencimentos ainda não pagos serão removidos. Os já pagos ficam no histórico."
    confirm-label="Excluir"
    destructive
    @confirm="remove"
  />
</template>
