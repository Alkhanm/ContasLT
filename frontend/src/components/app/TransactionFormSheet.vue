<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ArrowLeftRight, Camera, ChevronDown, CreditCard, Repeat, Trash2, TriangleAlert } from 'lucide-vue-next'
import { receiptsApi, transactionsApi, transfersApi } from '@/api'
import type { ReceiptScan, TransactionEditScope, TransactionInput, TransactionItemInput, TransactionType } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import AccountSelect from './AccountSelect.vue'
import CategorySelect from './CategorySelect.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import DateInput from './DateInput.vue'
import FormField from './FormField.vue'
import FormSheet from './FormSheet.vue'
import MoneyInput from './MoneyInput.vue'
import ReceiptItemsEditor from './ReceiptItemsEditor.vue'
import TypeToggle, { type FormKind } from './TypeToggle.vue'
import { dateShort, money, todayIso } from '@/lib/format'
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
  /** Em ocorrência de conta recorrente: aplicar só nela ou nela e nos próximos vencimentos. */
  scope: 'THIS' as TransactionEditScope,
  /** Transferência para cartão: mês da fatura que ela paga (AAAA-MM). */
  invoiceMonth: null as string | null,
})
const scopes: { value: TransactionEditScope; label: string }[] = [
  { value: 'THIS', label: 'Só este' },
  { value: 'FOLLOWING', label: 'Este e os próximos' },
]
const errors = ref<Record<string, string>>({})
const scanning = ref(false)
const showItems = ref(false)
const receiptUrl = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
/** Lançamento já existente que parece ser deste mesmo comprovante (chave de acesso, ou data e valor iguais). */
const duplicate = ref<Pick<ReceiptScan, 'duplicateOfTransactionId' | 'duplicateOfDescription' | 'duplicateOfDate'> | null>(null)
/** Vencimento pendente de conta recorrente que o comprovante lido parece pagar. */
const recurringMatch = ref<ReceiptScan | null>(null)
/** Quando aceito: salvar grava o comprovante por cima desse vencimento em vez de criar um lançamento. */
const settleOccurrenceId = ref<number | null>(null)

function acceptRecurringMatch() {
  const m = recurringMatch.value
  if (!m?.recurringMatchTransactionId) return
  settleOccurrenceId.value = m.recurringMatchTransactionId
  if (m.recurringMatchDescription) form.description = m.recurringMatchDescription
  if (m.recurringMatchCategoryId) form.categoryId = m.recurringMatchCategoryId
  if (m.recurringMatchAccountId) form.accountId = m.recurringMatchAccountId
  form.paid = true
}


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
  duplicate.value = null
  recurringMatch.value = null
  settleOccurrenceId.value = null
  try {
    const scan = await receiptsApi.scan(file, editing.value?.id)
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
    form.paid = scan.paid
    loadReceiptImage(scan.receiptFile)
    // Pago no crédito: a compra vai para o cartão, não para a conta corrente.
    const method = (scan.paymentMethod ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
    if (method.includes('credito') && !editing.value) {
      const cardAccount = catalog.activeAccounts.find((a) => a.type === 'CREDIT_CARD')
      if (cardAccount) form.accountId = cardAccount.id
    }
    if (scan.duplicateOfTransactionId) {
      duplicate.value = scan
      notifyError(new Error(`Este cupom já foi lançado em ${scan.duplicateOfDate ? dateShort(scan.duplicateOfDate) : ''}: ${scan.duplicateOfDescription}`))
    } else if (scan.recurringMatchTransactionId && !editing.value) {
      recurringMatch.value = scan
      acceptRecurringMatch()
      notifySuccess(`Parece o pagamento de ${scan.recurringMatchDescription}. Confira e salve.`)
    } else {
      notifySuccess(!scan.paid && scan.date
        ? `Fatura lida: vence em ${dateShort(scan.date)}. Confira os dados.`
        : scan.items.length
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
/** "Compras não detalhadas" da fatura: só categoria e observações são editáveis. */
const invoiceGap = computed(() => editing.value?.invoiceGap === true)
const toCard = computed(() => catalog.account(form.toAccountId)?.type === 'CREDIT_CARD')
/** Lançamento existente que é perna de uma transferência vinculada. */
const linked = computed(() => editing.value?.transferId != null)
const type = computed<TransactionType>(() => (form.kind === 'INCOME' ? 'INCOME' : 'EXPENSE'))
const title = computed(() => (editing.value
  ? 'Editar lançamento'
  : form.kind === 'TRANSFER' ? (toCard ? 'Pagar fatura do cartão' : 'Nova transferência') : form.kind === 'INCOME' ? 'Nova receita' : 'Nova despesa'))

watch(() => ui.transactionFormOpen, async (open) => {
  if (!open) return
  await catalog.load()
  errors.value = {}
  duplicate.value = null
  recurringMatch.value = null
  settleOccurrenceId.value = null
  form.scope = 'THIS'
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
    form.invoiceMonth = null
    loadReceiptImage(tx.receiptFile)
  } else {
    const d = ui.formDefaults
    form.kind = d.transfer ? 'TRANSFER' : d.type ?? 'EXPENSE'
    form.amount = d.transfer?.amount ?? null
    form.description = d.transfer?.description ?? ''
    form.dueDate = d.dueDate ?? todayIso()
    form.categoryId = null
    form.accountId = d.accountId ?? catalog.activeAccounts[0]?.id ?? null
    form.toAccountId = d.transfer?.toAccountId ?? null
    form.invoiceMonth = d.transfer?.invoiceMonth ?? null
    form.paid = !!d.transfer
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
        invoiceMonth: toCard.value ? form.invoiceMonth : null,
      })
      notifySuccess(toCard.value ? 'Pagamento da fatura registrado' : 'Transferência registrada')
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
        const scope = editing.value.recurringBillId ? form.scope : undefined
        await transactionsApi.update(editing.value.id, input, scope)
        notifySuccess(scope === 'FOLLOWING' ? 'Este e os próximos vencimentos atualizados' : 'Lançamento atualizado')
      } else if (settleOccurrenceId.value) {
        await transactionsApi.update(settleOccurrenceId.value, input)
        notifySuccess('Vencimento pago com o comprovante')
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
      <TypeToggle v-model="form.kind" :disabled="!!editing?.recurringBillId || linked || invoiceGap" :allow-transfer="!editing" />

      <p v-if="linked" class="flex items-start gap-2 rounded-lg bg-muted px-3 py-2 text-xs text-muted-foreground">
        <ArrowLeftRight class="mt-0.5 size-3.5 shrink-0" />
        <span class="flex-1">
          Parte de uma transferência {{ editing?.type === 'EXPENSE' ? 'para' : 'vinda de' }} <strong class="text-foreground">{{ editing?.transferPeerAccountName }}</strong>. Não conta como {{ editing?.type === 'EXPENSE' ? 'despesa' : 'receita' }}.
          <button type="button" class="ml-1 font-semibold text-primary underline-offset-2 hover:underline" @click="confirmUnlink = true">Desvincular</button>
        </span>
      </p>

      <p v-if="invoiceGap" class="flex items-start gap-2 rounded-lg bg-muted px-3 py-2 text-xs text-muted-foreground">
        <CreditCard class="mt-0.5 size-3.5 shrink-0" />
        <span>Valor calculado pela fatura do cartão: o que foi pago além das compras registradas. Registre as compras do período e ele diminui sozinho. Aqui dá para mudar só a categoria e as observações.</span>
      </p>

      <FormField label="Valor" for="tx-amount" :error="errors.amount">
        <MoneyInput id="tx-amount" v-model="form.amount" :invalid="!!errors.amount" :autofocus="!editing" :disabled="invoiceGap" />
      </FormField>

      <div v-if="!isTransfer && !linked && !invoiceGap && form.kind === 'EXPENSE'" class="flex items-center gap-3">
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

      <div v-if="recurringMatch && !isTransfer" class="space-y-2 rounded-lg bg-primary/5 px-3 py-2 text-xs">
        <p class="flex items-start gap-2">
          <Repeat class="mt-0.5 size-3.5 shrink-0 text-primary" />
          <span>
            Parece o pagamento de <strong>{{ recurringMatch.recurringMatchDescription }}</strong>, vencimento previsto
            para {{ recurringMatch.recurringMatchDate ? dateShort(recurringMatch.recurringMatchDate) : '' }}<template v-if="recurringMatch.recurringMatchAmount != null"> ({{ money(recurringMatch.recurringMatchAmount) }} estimado)</template>.
            <template v-if="settleOccurrenceId">Ao salvar, esse vencimento fica pago com o valor e a data do comprovante.</template>
          </span>
        </p>
        <span role="radiogroup" class="inline-flex rounded-md bg-muted p-0.5">
          <button type="button" role="radio" :aria-checked="settleOccurrenceId !== null" :class="cn('rounded px-2 py-1 font-semibold', settleOccurrenceId !== null ? 'bg-card shadow-xs' : 'text-muted-foreground')" @click="acceptRecurringMatch">Pagar o vencimento</button>
          <button type="button" role="radio" :aria-checked="settleOccurrenceId === null" :class="cn('rounded px-2 py-1 font-semibold', settleOccurrenceId === null ? 'bg-card shadow-xs' : 'text-muted-foreground')" @click="settleOccurrenceId = null">Criar novo lançamento</button>
        </span>
      </div>

      <p v-if="duplicate && !isTransfer" class="flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/10 px-3 py-2 text-xs" role="alert">
        <TriangleAlert class="mt-0.5 size-3.5 shrink-0 text-warning" />
        <span class="flex-1">
          <strong>Possível duplicata.</strong> Este comprovante parece já lançado{{ duplicate.duplicateOfDate ? ` em ${dateShort(duplicate.duplicateOfDate)}` : '' }}:
          <span class="font-medium">{{ duplicate.duplicateOfDescription }}</span>. Confira antes de salvar.
        </span>
      </p>

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
            <DateInput id="tx-date" v-model="form.dueDate" />
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
          <template v-if="toCard">
            Pagamento de fatura: sai da conta e quita o cartão. Não é despesa (as compras já são). O que exceder as compras registradas da fatura vira "compras não detalhadas" no cartão.
          </template>
          <template v-else>Sai de uma conta e entra na outra. Não conta como despesa nem como receita.</template>
        </p>
      </template>

      <template v-else>
        <FormField label="Descrição" for="tx-description" :error="errors.description">
          <Input id="tx-description" v-model="form.description" placeholder="Ex.: Conta de luz" maxlength="140" autocomplete="off" :disabled="invoiceGap" />
        </FormField>

        <div class="grid grid-cols-2 gap-3">
          <FormField :label="type === 'INCOME' ? 'Data' : 'Vencimento'" for="tx-date" :error="errors.dueDate">
            <DateInput id="tx-date" v-model="form.dueDate" :disabled="invoiceGap" />
          </FormField>
          <FormField label="Conta" for="tx-account" :error="errors.accountId">
            <AccountSelect id="tx-account" v-model="form.accountId" :disabled="invoiceGap" />
          </FormField>
        </div>

        <FormField v-if="!linked" label="Categoria" for="tx-category" :error="errors.categoryId">
          <CategorySelect id="tx-category" v-model="form.categoryId" :type="type" />
        </FormField>

        <div v-if="type === 'EXPENSE' && !invoiceGap" class="rounded-lg border">
          <button type="button" class="flex w-full items-center justify-between px-4 py-2.5 text-sm font-semibold" :aria-expanded="showItems" @click="showItems = !showItems">
            <span>Itens<span v-if="form.items.length" class="ml-1 font-normal text-muted-foreground">({{ form.items.length }})</span></span>
            <ChevronDown :class="cn('size-4 text-muted-foreground transition-transform', showItems && 'rotate-180')" />
          </button>
          <div v-if="showItems" class="border-t px-3 py-3">
            <ReceiptItemsEditor v-model="form.items" :amount="form.amount" @use-sum="form.amount = $event" />
          </div>
        </div>

        <label v-if="!invoiceGap" class="flex items-center justify-between rounded-lg border px-4 py-3">
          <span class="text-sm font-semibold">{{ type === 'INCOME' ? 'Já recebido' : 'Já pago' }}</span>
          <Switch v-model="form.paid" />
        </label>

        <FormField label="Observações" for="tx-notes">
          <Textarea id="tx-notes" v-model="form.notes" rows="2" maxlength="500" placeholder="Opcional" />
        </FormField>
      </template>

      <div v-if="editing?.recurringBillId" class="space-y-2 rounded-lg bg-muted px-3 py-3">
        <p class="flex items-center gap-2 text-xs font-semibold">
          <Repeat class="size-3.5 shrink-0" /> Lançamento de conta recorrente. Aplicar alterações em:
        </p>
        <div role="radiogroup" class="grid grid-cols-2 gap-1 rounded-lg bg-background p-1">
          <button
            v-for="s in scopes"
            :key="s.value"
            type="button"
            role="radio"
            :aria-checked="form.scope === s.value"
            :class="cn('h-8 rounded-md text-xs font-semibold transition-colors outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
              form.scope === s.value ? 'bg-primary text-primary-foreground shadow-xs' : 'text-muted-foreground hover:text-foreground')"
            @click="form.scope = s.value"
          >
            {{ s.label }}
          </button>
        </div>
        <p class="text-xs text-muted-foreground">
          <template v-if="form.scope === 'FOLLOWING'">
            Valor, descrição, conta e categoria passam para os próximos vencimentos pendentes. Se a data mudar, os próximos seguem o novo dia.
            "Pago" e observações valem só para este.
          </template>
          <template v-else>
            Só esta ocorrência muda; os próximos vencimentos continuam como estão.
          </template>
          <RouterLink :to="{ name: 'recurring' }" class="font-semibold text-primary underline-offset-2 hover:underline" @click="ui.closeTransactionForm()">Ver recorrentes</RouterLink>
        </p>
      </div>
    </form>

    <template #footer>
      <div class="flex items-center gap-2">
        <Button v-if="editing && !invoiceGap" type="button" variant="outline" size="icon" class="text-destructive" title="Excluir" aria-label="Excluir" @click="confirmDelete = true">
          <Trash2 />
        </Button>
        <Button
          v-if="editing && !editing.recurringBillId && !linked && !invoiceGap" type="button" variant="outline" class="h-11"
          title="Criar uma conta recorrente a partir deste lançamento" @click="ui.makeRecurring(editing)"
        >
          <Repeat /> Tornar recorrente
        </Button>
        <Button type="submit" form="transaction-form" class="h-11 flex-1 text-base font-semibold" :disabled="saving">
          {{ saving ? 'Salvando…' : editing ? 'Salvar alterações' : settleOccurrenceId ? 'Pagar vencimento' : isTransfer ? (toCard ? 'Pagar fatura' : 'Transferir') : 'Adicionar' }}
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
