<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ArrowLeftRight, CheckCircle2, CreditCard, FileSpreadsheet, FileText, Link2, Receipt, Repeat, Trash2, Upload } from 'lucide-vue-next'
import { importsApi, transfersApi } from '@/api'
import type { ImportBatch, ImportPreview, ImportResult, PreviewRow } from '@/api/types'
import AccountSelect from '@/components/app/AccountSelect.vue'
import CategorySelect from '@/components/app/CategorySelect.vue'
import ConfirmDialog from '@/components/app/ConfirmDialog.vue'
import FormField from '@/components/app/FormField.vue'
import Money from '@/components/app/Money.vue'
import SectionTitle from '@/components/app/SectionTitle.vue'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { dateNumeric, dateTime, money } from '@/lib/format'
import { notifyError, notifySuccess } from '@/lib/notify'
import { cn } from '@/lib/utils'
import { useCatalogStore } from '@/stores/catalog'
import { useUiStore } from '@/stores/ui'

interface ReviewRow extends PreviewRow {
  selected: boolean
  categoryId: number | null
  /** Quando há um lançamento manual parecido: vincular a ele (true) ou criar um novo (false). */
  link: boolean
  /** Linha que parece pagamento de fatura: registrar como pagamento do cartão escolhido (true) ou como despesa comum. */
  payInvoice: boolean
  invoiceCard: number | null
}

const catalog = useCatalogStore()
const ui = useUiStore()

const step = ref<'upload' | 'review' | 'done'>('upload')
const accountId = ref<number | null>(null)
const file = ref<File | null>(null)
const dragging = ref(false)
const busy = ref(false)
const preview = ref<ImportPreview | null>(null)
const rows = ref<ReviewRow[]>([])
const result = ref<ImportResult | null>(null)
const history = ref<ImportBatch[]>([])
const transferHints = ref(0)
const toDelete = ref<ImportBatch | null>(null)

onMounted(async () => {
  await catalog.load()
  accountId.value = catalog.activeAccounts[0]?.id ?? null
  loadHistory()
})

async function loadHistory() {
  try {
    history.value = await importsApi.history()
  } catch {
    // histórico é informativo; falha silenciosa
  }
}

function pick(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files?.[0]) file.value = input.files[0]
  input.value = ''
}

function drop(e: DragEvent) {
  dragging.value = false
  const f = e.dataTransfer?.files?.[0]
  if (f) file.value = f
}

async function analyze() {
  if (!file.value || !accountId.value) return
  busy.value = true
  try {
    preview.value = await importsApi.preview(accountId.value, file.value)
    rows.value = preview.value.rows.map((r) => ({
      ...r, selected: !r.duplicate, categoryId: r.suggestedCategoryId, link: r.matchTransactionId !== null,
      payInvoice: r.invoiceCardId !== null, invoiceCard: r.invoiceCardId,
    }))
    step.value = 'review'
  } catch (err) {
    notifyError(err)
  } finally {
    busy.value = false
  }
}

const selected = computed(() => rows.value.filter((r) => r.selected))
const linkedCount = computed(() => selected.value.filter((r) => r.link && r.matchTransactionId !== null).length)
const invoiceCount = computed(() => selected.value.filter((r) => r.payInvoice && r.invoiceCard !== null).length)
const cards = computed(() => catalog.activeAccounts.filter((a) => a.type === 'CREDIT_CARD'))
const selectedTotal = computed(() => selected.value.reduce((s, r) => s + (r.type === 'INCOME' ? r.amount : -r.amount), 0))
const allSelected = computed(() => rows.value.length > 0 && rows.value.every((r) => r.selected))

function toggleAll(value: boolean) {
  rows.value.forEach((r) => (r.selected = value))
}

async function confirm() {
  if (!preview.value || !selected.value.length) return
  busy.value = true
  try {
    result.value = await importsApi.confirm({
      accountId: preview.value.accountId,
      source: preview.value.source,
      filename: preview.value.filename,
      rows: selected.value.map((r) => {
        const linking = r.link && r.matchTransactionId !== null
        const paying = !linking && r.payInvoice && r.invoiceCard !== null
        return {
          date: r.date, description: r.description, amount: r.amount, type: r.type,
          categoryId: linking || paying ? null : r.categoryId, externalId: r.externalId, paid: true,
          learnRule: !linking && !paying && r.categoryId !== null,
          linkTransactionId: linking ? r.matchTransactionId : null,
          invoiceCardId: paying ? r.invoiceCard : null,
          invoiceMonth: paying ? r.invoiceMonth : null,
        }
      }),
    })
    step.value = 'done'
    notifySuccess(result.value.linked
      ? `${result.value.imported} importados, ${result.value.linked} ${result.value.linked === 1 ? 'vinculado' : 'vinculados'}`
      : result.value.invoicePayments
        ? `${result.value.imported} importados, ${result.value.invoicePayments} ${result.value.invoicePayments === 1 ? 'pagamento de fatura' : 'pagamentos de fatura'}`
        : `${result.value.imported} lançamentos importados`)
    ui.dataChanged()
    catalog.reloadAccounts()
    loadHistory()
    transfersApi.suggestions().then((r) => { transferHints.value = r.items.length }).catch(() => { transferHints.value = 0 })
  } catch (err) {
    notifyError(err)
  } finally {
    busy.value = false
  }
}

async function removeBatch() {
  const target = toDelete.value
  if (!target) return
  try {
    const { removed } = await importsApi.remove(target.id)
    notifySuccess(`Importação excluída, ${removed} ${removed === 1 ? 'lançamento removido' : 'lançamentos removidos'}`)
    ui.dataChanged()
    catalog.reloadAccounts()
    loadHistory()
  } catch (err) {
    notifyError(err)
  } finally {
    toDelete.value = null
  }
}

function reset() {
  step.value = 'upload'
  file.value = null
  preview.value = null
  rows.value = []
  result.value = null
}
</script>

<template>
  <div class="space-y-6">
    <template v-if="step === 'upload'">
      <section class="rounded-2xl border bg-card p-4 md:p-6">
        <div class="grid gap-4 md:grid-cols-[14rem_1fr]">
          <FormField label="Importar para a conta" for="imp-account">
            <AccountSelect id="imp-account" v-model="accountId" />
          </FormField>
          <FormField label="Arquivo">
            <label
              :class="cn('flex cursor-pointer flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed px-4 py-8 text-center transition-colors',
                dragging ? 'border-primary bg-primary/5' : 'hover:bg-accent/50')"
              @dragover.prevent="dragging = true"
              @dragleave="dragging = false"
              @drop.prevent="drop"
            >
              <input type="file" class="sr-only" accept=".ofx,.qfx,.xlsx,.xls,.xlsm" @change="pick" />
              <span class="inline-flex size-11 items-center justify-center rounded-full bg-primary/10 text-primary">
                <component :is="file ? (file.name.toLowerCase().endsWith('.ofx') ? FileText : FileSpreadsheet) : Upload" class="size-5" />
              </span>
              <template v-if="file">
                <span class="font-semibold">{{ file.name }}</span>
                <span class="text-xs text-muted-foreground">{{ (file.size / 1024).toFixed(0) }} KB · toque para trocar</span>
              </template>
              <template v-else>
                <span class="font-semibold">Solte o arquivo aqui ou toque para escolher</span>
                <span class="text-xs text-muted-foreground">OFX do seu banco ou planilha Excel com colunas Data, Descrição e Valor</span>
              </template>
            </label>
          </FormField>
        </div>
        <div class="mt-4 flex justify-end">
          <Button class="h-11 w-full md:w-auto" :disabled="!file || !accountId || busy" @click="analyze">
            {{ busy ? 'Lendo arquivo…' : 'Continuar' }}
          </Button>
        </div>
      </section>

      <section v-if="history.length">
        <SectionTitle title="Importações anteriores" />
        <ul class="divide-y rounded-2xl border bg-card px-4">
          <li v-for="b in history" :key="b.id" class="flex items-center gap-3 py-3 text-sm">
            <component :is="b.source === 'OFX' ? FileText : FileSpreadsheet" class="size-4 shrink-0 text-muted-foreground" />
            <span class="min-w-0 flex-1">
              <span class="block truncate font-semibold">{{ b.filename ?? 'arquivo' }}</span>
              <span class="block text-xs text-muted-foreground">{{ b.accountName }} · {{ dateTime(b.createdAt) }}</span>
            </span>
            <span class="text-xs text-muted-foreground">{{ b.importedCount }} {{ b.importedCount === 1 ? 'lançamento' : 'lançamentos' }}</span>
            <Button variant="ghost" size="icon-sm" class="text-muted-foreground hover:text-destructive" title="Excluir importação" aria-label="Excluir importação" @click="toDelete = b">
              <Trash2 />
            </Button>
          </li>
        </ul>
      </section>

      <ConfirmDialog
        :open="toDelete !== null"
        title="Excluir importação?"
        :description="toDelete ? `Os ${toDelete.importedCount} lançamentos criados por “${toDelete.filename ?? 'este arquivo'}” serão apagados. As regras de categoria aprendidas continuam.` : ''"
        confirm-label="Excluir tudo"
        destructive
        @update:open="(v) => !v && (toDelete = null)"
        @confirm="removeBatch"
      />
    </template>

    <template v-else-if="step === 'review' && preview">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div>
          <p class="font-semibold">{{ preview.filename }}</p>
          <p class="text-sm text-muted-foreground">
            {{ preview.total }} {{ preview.total === 1 ? 'lançamento encontrado' : 'lançamentos encontrados' }}<template v-if="preview.duplicates">, {{ preview.duplicates }} já importados (desmarcados)</template><template v-if="linkedCount">, {{ linkedCount }} {{ linkedCount === 1 ? 'será vinculado a um lançamento seu' : 'serão vinculados a lançamentos seus' }}</template><template v-if="invoiceCount">, {{ invoiceCount }} {{ invoiceCount === 1 ? 'pagamento de fatura' : 'pagamentos de fatura' }}</template>
          </p>
        </div>
        <Button variant="ghost" size="sm" @click="reset">Trocar arquivo</Button>
      </div>

      <div class="overflow-hidden rounded-2xl border bg-card">
        <div class="flex items-center gap-3 border-b bg-muted/50 px-4 py-2 text-xs font-semibold text-muted-foreground">
          <Checkbox :model-value="allSelected" aria-label="Selecionar todos" @update:model-value="toggleAll(!!$event)" />
          <span class="md:hidden">Selecionar todos</span>
          <span class="hidden w-20 md:block">Data</span>
          <span class="hidden flex-1 md:block">Descrição</span>
          <span class="hidden w-56 md:block">Categoria</span>
          <span class="hidden w-28 text-right md:block">Valor</span>
        </div>
        <ul class="divide-y">
          <li v-for="r in rows" :key="r.index" :class="cn('px-4 py-2.5', !r.selected && 'opacity-60')">
            <div class="flex items-start gap-3 md:items-center">
              <Checkbox v-model="r.selected" :aria-label="`Importar ${r.description}`" class="mt-1 md:mt-0" />
              <div class="min-w-0 flex-1 md:flex md:items-center md:gap-3">
                <div class="flex items-baseline justify-between gap-3 md:contents">
                  <span class="text-xs tabular text-muted-foreground md:w-20 md:shrink-0">{{ dateNumeric(r.date) }}</span>
                  <Money :value="r.amount" :type="r.type" signed class="text-sm font-semibold md:order-last md:w-28 md:text-right" />
                </div>
                <span class="min-w-0 md:flex-1">
                  <span class="block text-sm font-semibold md:truncate">{{ r.description }}</span>
                  <span v-if="r.duplicate" class="text-[11px] font-semibold text-warning">Já importado</span>
                  <span v-else-if="r.suggestedCategoryId && r.categoryId === r.suggestedCategoryId" class="text-[11px] text-muted-foreground">Categoria sugerida</span>
                </span>
                <span class="mt-2 block md:mt-0 md:w-56 md:shrink-0">
                  <span v-if="r.link && r.matchTransactionId !== null" class="flex h-8 items-center gap-1.5 rounded-md border border-dashed px-2 text-xs text-muted-foreground">
                    <Repeat v-if="r.matchRecurring" class="size-3.5 shrink-0 text-primary" />
                    <Link2 v-else class="size-3.5 shrink-0 text-primary" />
                    <span class="truncate">{{ r.matchRecurring ? 'Paga o vencimento' : r.matchCategoryName ?? 'Mantém o lançamento' }}</span>
                  </span>
                  <span v-else-if="r.payInvoice && r.invoiceCard !== null" class="flex h-8 items-center gap-1.5 rounded-md border border-dashed px-2 text-xs text-muted-foreground">
                    <CreditCard class="size-3.5 shrink-0 text-primary" />
                    <span class="truncate">Paga a fatura</span>
                  </span>
                  <CategorySelect v-else v-model="r.categoryId" :type="r.type" size="sm" />
                </span>
              </div>
            </div>
            <div v-if="r.invoiceCardId !== null && r.matchTransactionId === null && r.selected" class="mt-2 ml-7 space-y-2 rounded-lg bg-primary/5 px-3 py-2 text-xs">
              <p class="flex items-start gap-1.5">
                <CreditCard class="mt-0.5 size-3.5 shrink-0 text-primary" />
                <span>
                  Parece o pagamento da fatura de <strong>{{ catalog.account(r.invoiceCard)?.name ?? r.invoiceCardName }}</strong><template v-if="r.invoiceDueDate"> que vence em {{ dateNumeric(r.invoiceDueDate) }}</template><template v-if="r.invoiceTotal != null"> ({{ money(r.invoiceTotal) }} em compras registradas)</template>.
                  Não vira despesa: sai da conta e quita o cartão; o que exceder as compras registradas vira "compras não detalhadas".
                </span>
              </p>
              <div class="flex flex-wrap items-center gap-2">
                <span role="radiogroup" class="inline-flex rounded-md bg-muted p-0.5">
                  <button type="button" role="radio" :aria-checked="r.payInvoice" :class="cn('rounded px-2 py-1 font-semibold', r.payInvoice ? 'bg-card shadow-xs' : 'text-muted-foreground')" @click="r.payInvoice = true">Pagar fatura</button>
                  <button type="button" role="radio" :aria-checked="!r.payInvoice" :class="cn('rounded px-2 py-1 font-semibold', !r.payInvoice ? 'bg-card shadow-xs' : 'text-muted-foreground')" @click="r.payInvoice = false">Despesa comum</button>
                </span>
                <select v-if="cards.length > 1 && r.payInvoice" v-model="r.invoiceCard" class="h-7 rounded-md border bg-card px-1.5 text-xs" aria-label="Cartão">
                  <option v-for="c in cards" :key="c.id" :value="c.id">{{ c.name }}</option>
                </select>
              </div>
            </div>
            <div v-if="r.matchTransactionId !== null && r.selected" class="mt-2 ml-7 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg bg-primary/5 px-3 py-2 text-xs">
              <span class="flex min-w-0 flex-1 items-center gap-1.5">
                <Repeat v-if="r.matchRecurring" class="size-3.5 shrink-0 text-primary" />
                <Receipt v-else-if="r.matchHasReceipt" class="size-3.5 shrink-0 text-primary" />
                <Link2 v-else class="size-3.5 shrink-0 text-primary" />
                <span v-if="r.matchRecurring" class="min-w-0">
                  Parece o vencimento de <strong>{{ r.matchDescription }}</strong> previsto para {{ r.matchDate ? dateNumeric(r.matchDate) : '' }}<template v-if="r.matchAmount != null && r.matchAmount !== r.amount"> ({{ money(r.matchAmount) }} estimado)</template>. Vincular marca como pago com o valor e a data do extrato.
                </span>
                <span v-else class="truncate">
                  Você já lançou <strong>{{ r.matchDescription }}</strong> em {{ r.matchDate ? dateNumeric(r.matchDate) : '' }}<template v-if="r.matchItemCount"> ({{ r.matchItemCount }} {{ r.matchItemCount === 1 ? 'item' : 'itens' }})</template>
                </span>
              </span>
              <span role="radiogroup" class="inline-flex rounded-md bg-muted p-0.5">
                <button type="button" role="radio" :aria-checked="r.link" :class="cn('rounded px-2 py-1 font-semibold', r.link ? 'bg-card shadow-xs' : 'text-muted-foreground')" @click="r.link = true">Vincular</button>
                <button type="button" role="radio" :aria-checked="!r.link" :class="cn('rounded px-2 py-1 font-semibold', !r.link ? 'bg-card shadow-xs' : 'text-muted-foreground')" @click="r.link = false">Criar novo</button>
              </span>
            </div>
          </li>
        </ul>
      </div>

      <div class="sticky bottom-[calc(4.25rem+env(safe-area-inset-bottom))] z-20 flex items-center justify-between gap-3 rounded-2xl border bg-background/95 p-3 shadow-lg backdrop-blur md:bottom-4">
        <span class="text-sm">
          <span class="font-semibold">{{ selected.length }}</span> {{ selected.length === 1 ? 'selecionado' : 'selecionados' }}
          <Money :value="selectedTotal" class="ml-2 text-xs" />
        </span>
        <Button class="h-11" :disabled="!selected.length || busy" @click="confirm">
          {{ busy ? 'Importando…' : `Importar ${selected.length}` }}
        </Button>
      </div>
    </template>

    <template v-else-if="step === 'done' && result">
      <section class="flex flex-col items-center gap-3 rounded-2xl border bg-card px-6 py-12 text-center">
        <span class="inline-flex size-14 items-center justify-center rounded-full bg-income/15 text-income">
          <CheckCircle2 class="size-7" />
        </span>
        <h2 class="text-xl font-bold">Importação concluída</h2>
        <p class="text-sm text-muted-foreground">
          {{ result.imported }} {{ result.imported === 1 ? 'lançamento importado' : 'lançamentos importados' }}<template v-if="result.invoicePayments">, {{ result.invoicePayments }} {{ result.invoicePayments === 1 ? 'registrado como pagamento de fatura' : 'registrados como pagamentos de fatura' }}</template><template v-if="result.linked">, {{ result.linked }} {{ result.linked === 1 ? 'vinculado a um lançamento que você já tinha' : 'vinculados a lançamentos que você já tinha' }}</template><template v-if="result.skipped">, {{ result.skipped }} ignorados por já existirem</template>.
          As categorias escolhidas serão sugeridas nas próximas importações.
        </p>
        <RouterLink
          v-if="transferHints"
          :to="{ name: 'transactions', query: { cruzar: '1' } }"
          class="flex items-center gap-2 rounded-lg border border-primary/30 bg-primary/5 px-3 py-2 text-left text-sm"
        >
          <ArrowLeftRight class="size-4 shrink-0 text-primary" />
          <span>{{ transferHints }} {{ transferHints === 1 ? 'possível transferência entre contas encontrada' : 'possíveis transferências entre contas encontradas' }}. <span class="font-semibold text-primary">Revisar</span></span>
        </RouterLink>
        <div class="mt-3 flex gap-2">
          <Button variant="outline" @click="reset">Importar outro arquivo</Button>
          <Button as-child>
            <RouterLink :to="{ name: 'transactions' }">Ver lançamentos</RouterLink>
          </Button>
        </div>
      </section>
    </template>
  </div>
</template>
