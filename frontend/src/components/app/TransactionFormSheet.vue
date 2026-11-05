<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ArrowLeftRight, Camera, ChevronDown, Clock, CreditCard, Repeat, Trash2, TriangleAlert } from 'lucide-vue-next'
import { receiptsApi, transactionsApi, transfersApi } from '@/api'
import { fetchTransactions } from '@/composables/queries'
import type { Account, ReceiptScan, ReceiptScanAction, TransactionEditScope, TransactionInput, TransactionItemInput, TransactionPage, TransactionType } from '@/api/types'
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
import Money from './Money.vue'
import MoneyInput from './MoneyInput.vue'
import ReceiptItemsEditor from './ReceiptItemsEditor.vue'
import ReceiptScanNotice from './ReceiptScanNotice.vue'
import TypeToggle, { type FormKind } from './TypeToggle.vue'
import { dateShort, money, monthLabel, parseLocalDate, shiftMonth, todayIso } from '@/lib/format'
import { cn } from '@/lib/utils'
import { fieldErrors, notifyError, notifySuccess } from '@/lib/notify'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const catalog = useCatalogStore()
const auth = useAuthStore()
/** Leitura de comprovante só para contas premium; sem o usuário carregado ainda, mostra (o servidor decide). */
const canScan = computed(() => auth.user?.premium !== false)

const form = reactive({
  kind: 'EXPENSE' as FormKind,
  amount: null as number | null,
  description: '',
  dueDate: todayIso(),
  categoryId: null as string | null,
  accountId: null as string | null,
  /** Conta de destino, só para transferências. */
  toAccountId: null as string | null,
  paid: false,
  notes: '',
  merchant: '' as string,
  receiptFile: null as string | null,
  receiptAccessKey: null as string | null,
  items: [] as TransactionItemInput[],
  /** Em ocorrência de conta recorrente: aplicar só nela ou nela e nos próximos vencimentos. */
  scope: 'THIS' as TransactionEditScope,
  /** Pagamento de fatura de cartão: cartão e mês da fatura (AAAA-MM) cujas compras esta despesa agrega. */
  invoiceCardId: null as string | null,
  invoiceMonth: null as string | null,
})
const scopes: { value: TransactionEditScope; label: string }[] = [
  { value: 'THIS', label: 'Só este' },
  { value: 'FOLLOWING', label: 'Este e os próximos' },
]
const errors = ref<Record<string, string>>({})
const scanning = ref(false)
/** Esperando o worker do computador ler a foto (fila em produção). */
const waitingWorker = ref(false)
/** A foto ficou na fila com o leitor offline: salvar normalmente; os itens chegam depois. */
const deferred = ref(false)
/** Invalida uma espera em andamento quando o formulário fecha ou outra foto é escolhida. */
let pollToken = 0
const scanBusy = ref(false)
const showItems = ref(false)
const receiptUrl = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
/** Lançamento já existente que parece ser deste mesmo comprovante (chave de acesso, ou data e valor iguais). */
const duplicate = ref<Pick<ReceiptScan, 'duplicateOfTransactionId' | 'duplicateOfDescription' | 'duplicateOfDate'> | null>(null)
/** Vencimento pendente de conta recorrente que o comprovante lido parece pagar. */
const recurringMatch = ref<ReceiptScan | null>(null)
/** Quando aceito: salvar grava o comprovante por cima desse vencimento em vez de criar um lançamento. */
const settleOccurrenceId = ref<string | null>(null)

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
  deferred.value = false
  duplicate.value = null
  recurringMatch.value = null
  settleOccurrenceId.value = null
  const token = ++pollToken
  try {
    let scan = await receiptsApi.scan(file, editing.value?.id)
    if (scan.status === 'PENDING') {
      // Fila do worker: a foto já está guardada; mostra e espera o computador ler, se ele estiver online.
      form.kind = 'EXPENSE'
      form.receiptFile = scan.receiptFile
      form.receiptAccessKey = null
      loadReceiptImage(scan.receiptFile)
      const result = await waitForJob(scan, token)
      if (token !== pollToken) return
      if (!result) {
        deferred.value = true
        notifySuccess(scan.workerOnline ? 'O leitor demorou demais. Salve o lançamento; os itens entram quando a leitura terminar.'
          : 'Leitor offline. Foto guardada: salve o lançamento e os itens entram quando o computador ligar.')
        return
      }
      scan = result
    }
    if (scan.status === 'FAILED') {
      notifyError(new Error(scan.error || 'Não foi possível ler o comprovante. A foto continua anexada.'))
      return
    }
    applyScan(scan)
  } catch (err) {
    notifyError(err)
  } finally {
    if (token === pollToken) {
      scanning.value = false
      waitingWorker.value = false
    }
  }
}

/** Consulta a tarefa a cada 2,5 s enquanto o worker responde (até 3 min); null = ficou para depois. */
async function waitForJob(initial: ReceiptScan, token: number): Promise<ReceiptScan | null> {
  let online = initial.workerOnline
  const deadline = Date.now() + 180_000
  while (online && Date.now() < deadline) {
    waitingWorker.value = true
    await new Promise((r) => setTimeout(r, 2500))
    if (token !== pollToken || !ui.transactionFormOpen) return null
    const job = await receiptsApi.job(initial.jobId!, editing.value?.id)
    if (job.status !== 'PENDING') return job
    online = job.workerOnline
  }
  return null
}

/** Preenche o formulário com o que o modelo leu. */
function applyScan(scan: ReceiptScan) {
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
}

/** Aviso de leitura adiada no lançamento em edição: usar o lido, manter o lançado ou ler de novo. */
async function onScanAction(action: ReceiptScanAction) {
  const tx = editing.value
  if (!tx) return
  scanBusy.value = true
  try {
    const updated = await transactionsApi.receiptScan(tx.id, action)
    if (action === 'ACCEPT') {
      form.amount = updated.amount
      form.dueDate = updated.dueDate
    }
    ui.editTransaction(updated)
    ui.dataChanged()
    notifySuccess(action === 'ACCEPT' ? 'Valor e data do comprovante aplicados' : action === 'RETRY' ? 'Comprovante enviado de novo para leitura' : 'Aviso removido')
  } catch (err) {
    notifyError(err)
  } finally {
    scanBusy.value = false
  }
}
const saving = ref(false)
const confirmDelete = ref(false)
const confirmUnlink = ref(false)

const editing = computed(() => ui.editingTransaction)
const isTransfer = computed(() => form.kind === 'TRANSFER')
/** Cartões do usuário; a seção "pagamento de fatura" só existe se houver algum. */
const cards = computed(() => catalog.activeAccounts.filter((a) => a.type === 'CREDIT_CARD'))
/** Compra no cartão: a conta escolhida é um cartão (a compra fica agregada na fatura, fora das listas). */
const onCard = computed(() => catalog.account(form.accountId)?.type === 'CREDIT_CARD')
const isInvoice = computed(() => form.invoiceCardId !== null)
const invoiceCard = computed(() => catalog.account(form.invoiceCardId))
/** Resumo e compras da fatura escolhida, para mostrar o que fica agregado nesta despesa. */
const invoicePreview = ref<TransactionPage | null>(null)
const showInvoiceItems = ref(false)

/** Meses de fatura oferecidos: dois antes e um depois do mês da data, com o vencimento de cada um. */
const invoiceMonths = computed(() => {
  const base = form.dueDate ? form.dueDate.slice(0, 7) : todayIso().slice(0, 7)
  const card = invoiceCard.value
  const out: { value: string; label: string }[] = []
  for (let i = -2; i <= 1; i++) {
    const m = shiftMonth(base, i)
    const due = card?.dueDay ? ` · vence ${String(card.dueDay).padStart(2, '0')}/${m.slice(5, 7)}` : ''
    out.push({ value: m, label: `${monthLabel(m).toLowerCase()}${due}` })
  }
  if (form.invoiceMonth && !out.some((o) => o.value === form.invoiceMonth)) {
    out.unshift({ value: form.invoiceMonth, label: monthLabel(form.invoiceMonth).toLowerCase() })
  }
  return out
})

/** Fatura de vencimento mais próximo da data, para sugerir o mês. */
function nearestInvoiceMonth(card: Account, date: string): string {
  const base = date.slice(0, 7)
  if (!card.dueDay) return base
  const target = parseLocalDate(date).getTime()
  let best = base
  let bestDistance = Infinity
  for (let i = -1; i <= 1; i++) {
    const m = shiftMonth(base, i)
    const due = parseLocalDate(`${m}-${String(card.dueDay).padStart(2, '0')}`).getTime()
    const distance = Math.abs(due - target)
    if (distance < bestDistance) {
      bestDistance = distance
      best = m
    }
  }
  return best
}

function toggleInvoice(on: boolean) {
  if (!on) {
    form.invoiceCardId = null
    form.invoiceMonth = null
    return
  }
  const card = cards.value[0]
  if (!card) return
  form.invoiceCardId = card.id
  form.invoiceMonth = nearestInvoiceMonth(card, form.dueDate || todayIso())
}

watch(() => form.invoiceCardId, (id, old) => {
  const card = catalog.account(id)
  if (card && (old === null || !form.invoiceMonth)) form.invoiceMonth = nearestInvoiceMonth(card, form.dueDate || todayIso())
})

watch([() => form.invoiceCardId, () => form.invoiceMonth, () => ui.transactionFormOpen], async ([id, month, open]) => {
  invoicePreview.value = null
  if (!open || !id || !month) return
  try {
    invoicePreview.value = await fetchTransactions({ month, accountId: id, invoice: true })
  } catch {
    invoicePreview.value = null
  }
}, { immediate: true })

/** Pago além das compras registradas (compras sem comprovante lido). */
const invoiceUndetailed = computed(() => {
  const purchases = invoicePreview.value?.invoice?.purchases ?? 0
  return form.amount ? Math.max(0, form.amount - purchases) : 0
})
/** Lançamento existente que é perna de uma transferência vinculada. */
const linked = computed(() => editing.value?.transferId != null)
const type = computed<TransactionType>(() => (form.kind === 'INCOME' ? 'INCOME' : 'EXPENSE'))
const title = computed(() => (editing.value
  ? 'Editar lançamento'
  : form.kind === 'TRANSFER' ? 'Nova transferência' : isInvoice.value ? 'Pagar fatura do cartão' : form.kind === 'INCOME' ? 'Nova receita' : 'Nova despesa'))

watch(() => ui.transactionFormOpen, async (open) => {
  pollToken++
  scanning.value = false
  waitingWorker.value = false
  deferred.value = false
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
    form.invoiceCardId = tx.invoiceCardId
    form.invoiceMonth = tx.invoiceMonth
    showInvoiceItems.value = false
    loadReceiptImage(tx.receiptFile)
  } else {
    const d = ui.formDefaults
    form.kind = d.type ?? 'EXPENSE'
    form.amount = d.invoice?.amount ?? null
    form.description = d.invoice?.description ?? ''
    form.dueDate = d.dueDate ?? todayIso()
    form.categoryId = null
    form.accountId = d.accountId ?? catalog.activeAccounts.find((a) => a.type !== 'CREDIT_CARD')?.id ?? catalog.activeAccounts[0]?.id ?? null
    form.toAccountId = null
    form.invoiceCardId = d.invoice?.cardId ?? null
    form.invoiceMonth = d.invoice?.month ?? null
    showInvoiceItems.value = false
    form.paid = !!d.invoice
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
        invoiceCardId: type.value === 'EXPENSE' && !onCard.value ? form.invoiceCardId : null,
        invoiceMonth: type.value === 'EXPENSE' && !onCard.value ? form.invoiceMonth : null,
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
        notifySuccess(type.value === 'INCOME' ? 'Receita adicionada' : isInvoice.value ? 'Fatura registrada' : onCard.value ? 'Compra no cartão registrada' : 'Despesa adicionada')
      }
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
  const target = editing.value
  if (!target) return
  try {
    await transactionsApi.remove(target.id)
    notifySuccess('Lançamento excluído')
    confirmDelete.value = false
    ui.closeTransactionForm()
    ui.dataChanged()
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

      <div v-if="!isTransfer && !linked && !isInvoice && form.kind === 'EXPENSE' && (canScan || form.receiptFile)" class="flex items-center gap-3">
        <input ref="fileInput" type="file" accept="image/*" capture="environment" class="hidden" @change="onReceiptPicked">
        <Button v-if="canScan" type="button" variant="outline" class="h-10" :disabled="scanning" @click="pickReceipt">
          <Camera /> {{ scanning ? 'Lendo comprovante…' : form.receiptFile ? 'Trocar comprovante' : 'Ler comprovante' }}
        </Button>
        <button v-if="receiptUrl" type="button" class="group relative size-10 shrink-0 overflow-hidden rounded-md border" title="Abrir comprovante" @click="openReceipt">
          <img :src="receiptUrl" alt="Comprovante" class="size-full object-cover">
        </button>
        <span v-else-if="scanning" class="text-xs text-muted-foreground">Pode levar alguns segundos.</span>
        <span v-else class="text-xs text-muted-foreground">Foto do cupom: itens e valor preenchidos sozinhos.</span>
      </div>
      <p v-if="waitingWorker && scanning" class="-mt-1 text-xs text-muted-foreground">Lendo no seu computador… pode levar um minuto.</p>

      <p v-if="deferred && !isTransfer" class="flex items-start gap-2 rounded-lg bg-muted/60 px-3 py-2 text-xs text-muted-foreground">
        <Clock class="mt-0.5 size-3.5 shrink-0" />
        <span>Foto guardada, leitura pendente. Salve o lançamento normalmente: os itens entram sozinhos quando o computador com o leitor estiver ligado, e você é avisado se o valor não bater.</span>
      </p>
      <ReceiptScanNotice v-else-if="editing?.receiptScanStatus && editing.receiptFile === form.receiptFile && !isTransfer" :transaction="editing" :busy="scanBusy" @action="onScanAction" />

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
          Sai de uma conta e entra na outra. Não conta como despesa nem como receita.
        </p>
      </template>

      <template v-else>
        <FormField label="Descrição" for="tx-description" :error="errors.description">
          <Input id="tx-description" v-model="form.description" placeholder="Ex.: Conta de luz" maxlength="140" autocomplete="off" />
        </FormField>

        <div class="grid grid-cols-2 gap-3">
          <FormField :label="type === 'INCOME' ? 'Data' : 'Vencimento'" for="tx-date" :error="errors.dueDate">
            <DateInput id="tx-date" v-model="form.dueDate" />
          </FormField>
          <FormField label="Conta" for="tx-account" :error="errors.accountId">
            <AccountSelect id="tx-account" v-model="form.accountId" />
          </FormField>
        </div>

        <p v-if="onCard && type === 'EXPENSE'" class="flex items-start gap-2 rounded-lg bg-muted px-3 py-2 text-xs text-muted-foreground">
          <CreditCard class="mt-0.5 size-3.5 shrink-0" />
          <span>Compra no cartão: fica na fatura do período, fora da lista geral e dos totais. O que conta como despesa é a fatura paga.</span>
        </p>

        <div v-if="type === 'EXPENSE' && !onCard && !linked && cards.length" class="rounded-lg border">
          <label class="flex items-center justify-between px-4 py-2.5">
            <span class="flex items-center gap-2 text-sm font-semibold"><CreditCard class="size-4 text-muted-foreground" /> Pagamento de fatura de cartão</span>
            <Switch :model-value="isInvoice" @update:model-value="toggleInvoice" />
          </label>
          <div v-if="isInvoice" class="space-y-3 border-t px-4 py-3">
            <div class="grid grid-cols-2 gap-3">
              <FormField label="Cartão" for="tx-invoice-card">
                <select id="tx-invoice-card" v-model="form.invoiceCardId" class="h-9 w-full rounded-md border bg-transparent px-3 text-sm">
                  <option v-for="c in cards" :key="c.id" :value="c.id">{{ c.name }}</option>
                </select>
              </FormField>
              <FormField label="Fatura" for="tx-invoice-month">
                <select id="tx-invoice-month" v-model="form.invoiceMonth" class="h-9 w-full rounded-md border bg-transparent px-3 text-sm">
                  <option v-for="m in invoiceMonths" :key="m.value" :value="m.value">{{ m.label }}</option>
                </select>
              </FormField>
            </div>
            <div v-if="invoicePreview?.invoice" class="text-xs">
              <button type="button" class="flex w-full items-center justify-between font-semibold" :aria-expanded="showInvoiceItems" @click="showInvoiceItems = !showInvoiceItems">
                <span>
                  {{ invoicePreview.invoice.count }} {{ invoicePreview.invoice.count === 1 ? 'compra registrada' : 'compras registradas' }} · {{ money(invoicePreview.invoice.purchases) }}
                  <span v-if="invoiceUndetailed > 0" class="font-normal text-muted-foreground"> · não detalhado {{ money(invoiceUndetailed) }}</span>
                </span>
                <ChevronDown :class="cn('size-4 text-muted-foreground transition-transform', showInvoiceItems && 'rotate-180')" />
              </button>
              <p class="mt-1 text-muted-foreground">
                Compras de {{ dateShort(invoicePreview.invoice.start) }} a {{ dateShort(invoicePreview.invoice.end) }} ficam agregadas nesta despesa. Elas não aparecem na lista nem contam de novo.
              </p>
              <ul v-if="showInvoiceItems && invoicePreview.items.length" class="mt-2 divide-y rounded-md border">
                <li v-for="t in invoicePreview.items" :key="t.id" class="flex items-center justify-between gap-2 px-2 py-1.5">
                  <span class="min-w-0 truncate">{{ dateShort(t.dueDate) }} · {{ t.description }}<span v-if="t.items?.length" class="text-muted-foreground"> ({{ t.items.length }} itens)</span></span>
                  <Money :value="t.amount" :type="t.type" class="shrink-0 font-semibold" />
                </li>
              </ul>
              <p v-else-if="showInvoiceItems" class="mt-2 text-muted-foreground">Nenhuma compra registrada nesse período ainda. Comprovantes lidos depois entram aqui sozinhos.</p>
            </div>
          </div>
        </div>

        <FormField v-if="!linked" label="Categoria" for="tx-category" :error="errors.categoryId">
          <CategorySelect id="tx-category" v-model="form.categoryId" :type="type" />
        </FormField>

        <div v-if="type === 'EXPENSE' && !isInvoice" class="rounded-lg border">
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
        <Button v-if="editing" type="button" variant="outline" size="icon" class="text-destructive" title="Excluir" aria-label="Excluir" @click="confirmDelete = true">
          <Trash2 />
        </Button>
        <Button
          v-if="editing && !editing.recurringBillId && !linked" type="button" variant="outline" class="h-11"
          title="Criar uma conta recorrente a partir deste lançamento" @click="ui.makeRecurring(editing)"
        >
          <Repeat /> Tornar recorrente
        </Button>
        <Button type="submit" form="transaction-form" class="h-11 flex-1 text-base font-semibold" :disabled="saving">
          {{ saving ? 'Salvando…' : editing ? 'Salvar alterações' : settleOccurrenceId ? 'Pagar vencimento' : isTransfer ? 'Transferir' : isInvoice ? 'Registrar fatura' : 'Adicionar' }}
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
