<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ArrowLeftRight, Camera, ChevronDown, Repeat, Trash2 } from 'lucide-vue-next'
import { receiptsApi, transactionsApi, transfersApi } from '@/api'
import type { TransactionInput, TransactionItemInput, TransactionType } from '@/api/types'
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
import ReceiptItemsEditor from './ReceiptItemsEditor.vue'
import TypeToggle, { type FormKind } from './TypeToggle.vue'
import { dateShort, todayIso } from '@/lib/format'
import { cn } from '@/lib/utils'
import { fieldErrors, notifyError, notifySuccess } from '@/lib/notify'
import { useCatalogStore } from '@/stores/catalog'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const catalog = useCatalogStore()

const form = reactive({
  kind: 'EXPENSE' as FormKind,
  amount: null as number | null,
  description: '',
  dueDate: todayIso(),
  categoryId: null as number | null,
  accountId: null as number | null,
  /** Conta de destino, só para transferências. */
  toAccountId: null as number | null,
  paid: false,
  notes: '',
  merchant: '' as string,
  receiptFile: null as string | null,
  receiptAccessKey: null as string | null,
  items: [] as TransactionItemInput[],
})
const errors = ref<Record<string, string>>({})
const scanning = ref(false)
const showItems = ref(false)
const receiptUrl = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

function revokeReceiptUrl() {
  if (receiptUrl.value) URL.revokeObjectURL(receiptUrl.value)
  receiptUrl.value = null
}
onBeforeUnmount(revokeReceiptUrl)

async function loadReceiptImage(file: string | null) {
  revokeReceiptUrl()
  if (!file) return
  try {
    receiptUrl.value = await receiptsApi.imageUrl(file)
  } catch {
    receiptUrl.value = null
  }
}

function pickReceipt() {
  fileInput.value?.click()
}

function openReceipt() {
  if (receiptUrl.value) window.open(receiptUrl.value, '_blank')
}

async function onReceiptPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  scanning.value = true
  try {
    const scan = await receiptsApi.scan(file)
    form.kind = 'EXPENSE'
    form.receiptFile = scan.receiptFile
    form.receiptAccessKey = scan.accessKey
    form.merchant = scan.merchant
    if (scan.total != null) form.amount = scan.total
    if (scan.merchant && !form.description.trim()) form.description = scan.merchant
    if (scan.date) form.dueDate = scan.date
    if (!form.categoryId && scan.suggestedCategoryId) form.categoryId = scan.suggestedCategoryId
    form.items = scan.items.map((i) => ({ description: i.description, quantity: i.quantity, unitPrice: i.unitPrice, total: i.total }))
    showItems.value = form.items.length > 0
    form.paid = true
    loadReceiptImage(scan.receiptFile)
    if (scan.duplicateOfTransactionId) {
      notifyError(new Error(`Este cupom já foi lançado em ${scan.duplicateOfDate ? dateShort(scan.duplicateOfDate) : ''}: ${scan.duplicateOfDescription}`))
    } else {
      notifySuccess(scan.items.length
        ? `Comprovante lido: ${scan.items.length} ${scan.items.length === 1 ? 'item' : 'itens'}`
        : 'Comprovante lido. Confira os dados.')
    }
  } catch (err) {
    notifyError(err)
  } finally {
    scanning.value = false
  }
}
const saving = ref(false)
const confirmDelete = ref(false)
const confirmUnlink = ref(false)

const editing = computed(() => ui.editingTransaction)
const isTransfer = computed(() => form.kind === 'TRANSFER')
/** Lançamento existente que é perna de uma transferência vinculada. */
const linked = computed(() => editing.value?.transferId != null)
const type = computed<TransactionType>(() => (form.kind === 'INCOME' ? 'INCOME' : 'EXPENSE'))
const title = computed(() => (editing.value
  ? 'Editar lançamento'
  : form.kind === 'TRANSFER' ? 'Nova transferência' : form.kind === 'INCOME' ? 'Nova receita' : 'Nova despesa'))

watch(() => ui.transactionFormOpen, async (open) => {
  if (!open) return
  await catalog.load()
  errors.value = {}
  const tx = editing.value
  if (tx) {
    form.kind = tx.type
    form.amount = tx.amount
    form.description = tx.description
    form.dueDate = tx.dueDate
    form.categoryId = tx.categoryId
    form.accountId = tx.accountId
    form.paid = tx.paid
    form.notes = tx.notes ?? ''
    form.toAccountId = null
    form.merchant = tx.merchant ?? ''
    form.receiptFile = tx.receiptFile
    form.receiptAccessKey = tx.receiptAccessKey
    form.items = (tx.items ?? []).map((i) => ({ description: i.description, quantity: i.quantity, unitPrice: i.unitPrice, total: i.total }))
    showItems.value = form.items.length > 0
    loadReceiptImage(tx.receiptFile)
  } else {
    const d = ui.formDefaults
    form.kind = d.type ?? 'EXPENSE'
    form.amount = null
    form.description = ''
    form.dueDate = d.dueDate ?? todayIso()
    form.categoryId = null
    form.accountId = d.accountId ?? catalog.activeAccounts[0]?.id ?? null
    form.toAccountId = null
    form.paid = false
    form.notes = ''
    form.merchant = ''
    form.receiptFile = null
    form.receiptAccessKey = null
    form.items = []
    showItems.value = false
    revokeReceiptUrl()
  }
})

watch(() => form.kind, (kind) => {
  const c = catalog.category(form.categoryId)
  if (c && c.type !== kind) form.categoryId = null
  if (kind === 'TRANSFER') {
    form.paid = true
    if (!form.toAccountId) form.toAccountId = catalog.activeAccounts.find((a) => a.id !== form.accountId)?.id ?? null
  }
})

function validate(): boolean {
  const e: Record<string, string> = {}
  if (!form.amount || form.amount <= 0) e.amount = 'Informe um valor maior que zero'
  if (!isTransfer.value && !form.description.trim()) e.description = 'Informe a descrição'
  if (!form.dueDate) e.dueDate = 'Informe a data'
  if (!form.accountId) e.accountId = isTransfer.value ? 'Escolha a conta de origem' : 'Escolha a conta'
  if (isTransfer.value) {
    if (!form.toAccountId) e.toAccountId = 'Escolha a conta de destino'
    else if (form.toAccountId === form.accountId) e.toAccountId = 'Origem e destino precisam ser diferentes'
  }
  errors.value = e
  return Object.keys(e).length === 0
}

async function save() {
  if (!validate()) return
  saving.value = true
  try {
    if (isTransfer.value) {
      await transfersApi.create({
        fromAccountId: form.accountId!,
        toAccountId: form.toAccountId!,
        amount: form.amount!,
        date: form.dueDate,
        description: form.description.trim() || null,
        paid: form.paid,
      })
      notifySuccess('Transferência registrada')
    } else {
      const input: TransactionInput = {
        description: form.description.trim(),
        amount: form.amount!,
        type: type.value,
        dueDate: form.dueDate,
        paid: form.paid,
        notes: form.notes.trim() || null,
        accountId: form.accountId!,
        categoryId: form.categoryId,
        merchant: form.merchant.trim() || null,
        receiptFile: form.receiptFile,
        receiptAccessKey: form.receiptAccessKey,
        items: form.items.filter((i) => i.description.trim()).map((i) => ({ ...i, description: i.description.trim(), total: i.total || 0 })),
      }
      if (editing.value) {
        await transactionsApi.update(editing.value.id, input)
        notifySuccess('Lançamento atualizado')
      } else {
        await transactionsApi.create(input)
        notifySuccess(type.value === 'INCOME' ? 'Receita adicionada' : 'Despesa adicionada')
      }
    }
    ui.closeTransactionForm()
    ui.dataChanged()
    catalog.reloadAccounts()
  } catch (err) {
    errors.value = fieldErrors(err)
    notifyError(err)
  } finally {
    saving.value = false
  }
}

async function remove() {
  const target = editing.value
  if (!target) return
  try {
    await transactionsApi.remove(target.id)
    notifySuccess('Lançamento excluído')
    confirmDelete.value = false
    ui.closeTransactionForm()
    ui.dataChanged()
    catalog.reloadAccounts()
  } catch (err) {
    notifyError(err)
  }
}

async function unlink() {
  const target = editing.value
  if (!target?.transferId) return
  try {
    await transfersApi.unlink(target.transferId)
    notifySuccess('Transferência desvinculada; os dois lançamentos voltam a contar no resultado')
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
      <TypeToggle v-model="form.kind" :disabled="!!editing?.recurringBillId || linked" :allow-transfer="!editing" />

      <p v-if="linked" class="flex items-start gap-2 rounded-lg bg-muted px-3 py-2 text-xs text-muted-foreground">
        <ArrowLeftRight class="mt-0.5 size-3.5 shrink-0" />
        <span class="flex-1">
          Parte de uma transferência {{ editing?.type === 'EXPENSE' ? 'para' : 'vinda de' }} <strong class="text-foreground">{{ editing?.transferPeerAccountName }}</strong>. Não conta como {{ editing?.type === 'EXPENSE' ? 'despesa' : 'receita' }}.
          <button type="button" class="ml-1 font-semibold text-primary underline-offset-2 hover:underline" @click="confirmUnlink = true">Desvincular</button>
        </span>
      </p>

      <FormField label="Valor" for="tx-amount" :error="errors.amount">
        <MoneyInput id="tx-amount" v-model="form.amount" :invalid="!!errors.amount" :autofocus="!editing" />
      </FormField>

      <div v-if="!isTransfer && !linked && form.kind === 'EXPENSE'" class="flex items-center gap-3">
        <input ref="fileInput" type="file" accept="image/*" capture="environment" class="hidden" @change="onReceiptPicked">
        <Button type="button" variant="outline" class="h-10" :disabled="scanning" @click="pickReceipt">
          <Camera /> {{ scanning ? 'Lendo comprovante…' : form.receiptFile ? 'Trocar comprovante' : 'Ler comprovante' }}
        </Button>
        <button v-if="receiptUrl" type="button" class="group relative size-10 shrink-0 overflow-hidden rounded-md border" title="Abrir comprovante" @click="openReceipt">
          <img :src="receiptUrl" alt="Comprovante" class="size-full object-cover">
        </button>
        <span v-else-if="scanning" class="text-xs text-muted-foreground">Pode levar alguns segundos.</span>
        <span v-else class="text-xs text-muted-foreground">Foto do cupom: itens e valor preenchidos sozinhos.</span>
      </div>

      <template v-if="isTransfer">
        <div class="grid grid-cols-2 gap-3">
          <FormField label="De" for="tx-account" :error="errors.accountId">
            <AccountSelect id="tx-account" v-model="form.accountId" />
          </FormField>
          <FormField label="Para" for="tx-to-account" :error="errors.toAccountId">
            <AccountSelect id="tx-to-account" v-model="form.toAccountId" />
          </FormField>
        </div>

        <div class="grid grid-cols-2 gap-3">
          <FormField label="Data" for="tx-date" :error="errors.dueDate">
            <Input id="tx-date" v-model="form.dueDate" type="date" />
          </FormField>
          <FormField label="Descrição" for="tx-description" :error="errors.description">
            <Input id="tx-description" v-model="form.description" placeholder="Transferência" maxlength="140" autocomplete="off" />
          </FormField>
        </div>

        <label class="flex items-center justify-between rounded-lg border px-4 py-3">
          <span class="text-sm font-semibold">Já efetuada</span>
          <Switch v-model="form.paid" />
        </label>

        <p class="text-xs text-muted-foreground">
          Sai de uma conta e entra na outra. Não conta como despesa nem como receita.
        </p>
      </template>

      <template v-else>
        <FormField label="Descrição" for="tx-description" :error="errors.description">
          <Input id="tx-description" v-model="form.description" placeholder="Ex.: Conta de luz" maxlength="140" autocomplete="off" />
        </FormField>

        <div class="grid grid-cols-2 gap-3">
          <FormField :label="type === 'INCOME' ? 'Data' : 'Vencimento'" for="tx-date" :error="errors.dueDate">
            <Input id="tx-date" v-model="form.dueDate" type="date" />
          </FormField>
          <FormField label="Conta" for="tx-account" :error="errors.accountId">
            <AccountSelect id="tx-account" v-model="form.accountId" />
          </FormField>
        </div>

        <FormField v-if="!linked" label="Categoria" for="tx-category" :error="errors.categoryId">
          <CategorySelect id="tx-category" v-model="form.categoryId" :type="type" />
        </FormField>

        <div v-if="type === 'EXPENSE'" class="rounded-lg border">
          <button type="button" class="flex w-full items-center justify-between px-4 py-2.5 text-sm font-semibold" :aria-expanded="showItems" @click="showItems = !showItems">
            <span>Itens<span v-if="form.items.length" class="ml-1 font-normal text-muted-foreground">({{ form.items.length }})</span></span>
            <ChevronDown :class="cn('size-4 text-muted-foreground transition-transform', showItems && 'rotate-180')" />
          </button>
          <div v-if="showItems" class="border-t px-3 py-3">
            <ReceiptItemsEditor v-model="form.items" :amount="form.amount" @use-sum="form.amount = $event" />
          </div>
        </div>

        <label class="flex items-center justify-between rounded-lg border px-4 py-3">
          <span class="text-sm font-semibold">{{ type === 'INCOME' ? 'Já recebido' : 'Já pago' }}</span>
          <Switch v-model="form.paid" />
        </label>

        <FormField label="Observações" for="tx-notes">
          <Textarea id="tx-notes" v-model="form.notes" rows="2" maxlength="500" placeholder="Opcional" />
        </FormField>
      </template>

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
          {{ saving ? 'Salvando…' : editing ? 'Salvar alterações' : isTransfer ? 'Transferir' : 'Adicionar' }}
        </Button>
      </div>
    </template>
  </FormSheet>

  <ConfirmDialog
    v-model:open="confirmDelete"
    title="Excluir lançamento?"
    :description="linked ? 'A outra parte da transferência continua existindo e volta a contar como lançamento comum.' : 'Esta ação não pode ser desfeita.'"
    confirm-label="Excluir"
    destructive
    @confirm="remove"
  />

  <ConfirmDialog
    v-model:open="confirmUnlink"
    title="Desvincular transferência?"
    description="Os dois lançamentos continuam existindo, mas voltam a contar como despesa e receita."
    confirm-label="Desvincular"
    @confirm="unlink"
  />
</template>
