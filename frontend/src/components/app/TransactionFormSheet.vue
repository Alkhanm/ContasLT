<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { Repeat, Trash2 } from 'lucide-vue-next'
import { transactionsApi } from '@/api'
import type { TransactionInput, TransactionType } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import AccountSelect from './AccountSelect.vue'
import CategorySelect from './CategorySelect.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import FormField from './FormField.vue'
import FormSheet from './FormSheet.vue'
import MoneyInput from './MoneyInput.vue'
import TypeToggle from './TypeToggle.vue'
import { todayIso } from '@/lib/format'
import { fieldErrors, notifyError, notifySuccess } from '@/lib/notify'
import { useCatalogStore } from '@/stores/catalog'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const catalog = useCatalogStore()

const form = reactive({
  type: 'EXPENSE' as TransactionType,
  amount: null as number | null,
  description: '',
  dueDate: todayIso(),
  categoryId: null as number | null,
  accountId: null as number | null,
  paid: false,
  notes: '',
})
const errors = ref<Record<string, string>>({})
const saving = ref(false)
const confirmDelete = ref(false)

const editing = computed(() => ui.editingTransaction)
const title = computed(() => (editing.value ? 'Editar lançamento' : form.type === 'INCOME' ? 'Nova receita' : 'Nova despesa'))

watch(() => ui.transactionFormOpen, async (open) => {
  if (!open) return
  await catalog.load()
  errors.value = {}
  const tx = editing.value
  if (tx) {
    form.type = tx.type
    form.amount = tx.amount
    form.description = tx.description
    form.dueDate = tx.dueDate
    form.categoryId = tx.categoryId
    form.accountId = tx.accountId
    form.paid = tx.paid
    form.notes = tx.notes ?? ''
  } else {
    const d = ui.formDefaults
    form.type = d.type ?? 'EXPENSE'
    form.amount = null
    form.description = ''
    form.dueDate = d.dueDate ?? todayIso()
    form.categoryId = null
    form.accountId = d.accountId ?? catalog.activeAccounts[0]?.id ?? null
    form.paid = false
    form.notes = ''
  }
})

watch(() => form.type, (type) => {
  const c = catalog.category(form.categoryId)
  if (c && c.type !== type) form.categoryId = null
})

function validate(): boolean {
  const e: Record<string, string> = {}
  if (!form.amount || form.amount <= 0) e.amount = 'Informe um valor maior que zero'
  if (!form.description.trim()) e.description = 'Informe a descrição'
  if (!form.dueDate) e.dueDate = 'Informe a data'
  if (!form.accountId) e.accountId = 'Escolha a conta'
  errors.value = e
  return Object.keys(e).length === 0
}

async function save() {
  if (!validate()) return
  saving.value = true
  const input: TransactionInput = {
    description: form.description.trim(),
    amount: form.amount!,
    type: form.type,
    dueDate: form.dueDate,
    paid: form.paid,
    notes: form.notes.trim() || null,
    accountId: form.accountId!,
    categoryId: form.categoryId,
  }
  try {
    if (editing.value) {
      await transactionsApi.update(editing.value.id, input)
      notifySuccess('Lançamento atualizado')
    } else {
      await transactionsApi.create(input)
      notifySuccess(form.type === 'INCOME' ? 'Receita adicionada' : 'Despesa adicionada')
    }
    ui.closeTransactionForm()
    ui.dataChanged()
  } catch (err) {
    errors.value = fieldErrors(err)
    notifyError(err)
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!editing.value) return
  try {
    await transactionsApi.remove(editing.value.id)
    notifySuccess('Lançamento excluído')
    confirmDelete.value = false
    ui.closeTransactionForm()
    ui.dataChanged()
  } catch (err) {
    notifyError(err)
  }
}
</script>

<template>
  <FormSheet :open="ui.transactionFormOpen" :title="title" @update:open="(v) => !v && ui.closeTransactionForm()">
    <form id="transaction-form" class="space-y-4" @submit.prevent="save">
      <TypeToggle v-model="form.type" :disabled="!!editing?.recurringBillId" />

      <FormField label="Valor" for="tx-amount" :error="errors.amount">
        <MoneyInput id="tx-amount" v-model="form.amount" :invalid="!!errors.amount" :autofocus="!editing" />
      </FormField>

      <FormField label="Descrição" for="tx-description" :error="errors.description">
        <Input id="tx-description" v-model="form.description" placeholder="Ex.: Conta de luz" maxlength="140" autocomplete="off" />
      </FormField>

      <div class="grid grid-cols-2 gap-3">
        <FormField :label="form.type === 'INCOME' ? 'Data' : 'Vencimento'" for="tx-date" :error="errors.dueDate">
          <Input id="tx-date" v-model="form.dueDate" type="date" />
        </FormField>
        <FormField label="Conta" for="tx-account" :error="errors.accountId">
          <AccountSelect id="tx-account" v-model="form.accountId" />
        </FormField>
      </div>

      <FormField label="Categoria" for="tx-category" :error="errors.categoryId">
        <CategorySelect id="tx-category" v-model="form.categoryId" :type="form.type" />
      </FormField>

      <label class="flex items-center justify-between rounded-lg border px-4 py-3">
        <span class="text-sm font-semibold">{{ form.type === 'INCOME' ? 'Já recebido' : 'Já pago' }}</span>
        <Switch v-model="form.paid" />
      </label>

      <FormField label="Observações" for="tx-notes">
        <Textarea id="tx-notes" v-model="form.notes" rows="2" maxlength="500" placeholder="Opcional" />
      </FormField>

      <p v-if="editing?.recurringBillId" class="flex items-start gap-2 rounded-lg bg-muted px-3 py-2 text-xs text-muted-foreground">
        <Repeat class="mt-0.5 size-3.5 shrink-0" />
        <span>
          Este lançamento vem de uma conta recorrente. Alterações aqui valem só para esta ocorrência.
          <RouterLink :to="{ name: 'recurring' }" class="font-semibold text-primary underline-offset-2 hover:underline" @click="ui.closeTransactionForm()">Ver recorrentes</RouterLink>
        </span>
      </p>
    </form>

    <template #footer>
      <div class="flex items-center gap-2">
        <Button v-if="editing" type="button" variant="outline" size="icon" class="text-destructive" title="Excluir" aria-label="Excluir" @click="confirmDelete = true">
          <Trash2 />
        </Button>
        <Button type="submit" form="transaction-form" class="h-11 flex-1 text-base font-semibold" :disabled="saving">
          {{ saving ? 'Salvando…' : editing ? 'Salvar alterações' : 'Adicionar' }}
        </Button>
      </div>
    </template>
  </FormSheet>

  <ConfirmDialog
    v-model:open="confirmDelete"
    title="Excluir lançamento?"
    description="Esta ação não pode ser desfeita."
    confirm-label="Excluir"
    destructive
    @confirm="remove"
  />
</template>
