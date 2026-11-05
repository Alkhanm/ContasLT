<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ChartPie, ChevronRight, Download, LogOut, Monitor, Moon, Plus, ShoppingBasket, Sun, Tag, Trash2, Upload, Wallet } from 'lucide-vue-next'
import { backupApi, rulesApi } from '@/api'
import { useRules } from '@/composables/queries'
import type { CategoryRule } from '@/api/types'
import CategoryIcon from '@/components/app/CategoryIcon.vue'
import CategorySelect from '@/components/app/CategorySelect.vue'
import ConfirmDialog from '@/components/app/ConfirmDialog.vue'
import SectionTitle from '@/components/app/SectionTitle.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { notifyError, notifySuccess } from '@/lib/notify'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import { useUiStore } from '@/stores/ui'
import { queryClient } from '@/lib/queryClient'
import { useThemeStore, type ThemePreference } from '@/stores/theme'

const auth = useAuthStore()
const catalog = useCatalogStore()
const theme = useThemeStore()
const ui = useUiStore()
const router = useRouter()

const { data: rulesData, error: rulesError, refetch: loadRules } = useRules()
const rules = computed(() => rulesData.value ?? [])
watch(rulesError, (err) => { if (err) notifyError(err) })
const keyword = ref('')
const ruleCategoryId = ref<string | null>(null)
const ruleType = ref<'EXPENSE' | 'INCOME'>('EXPENSE')
const saving = ref(false)

const themes: { value: ThemePreference; label: string; icon: typeof Sun }[] = [
  { value: 'light', label: 'Claro', icon: Sun },
  { value: 'dark', label: 'Escuro', icon: Moon },
  { value: 'system', label: 'Sistema', icon: Monitor },
]

onMounted(() => catalog.load())

// ---------- Backup (premium) ----------
const backupBusy = ref<'export' | 'import' | null>(null)
const backupInput = ref<HTMLInputElement | null>(null)
const backupFile = ref<File | null>(null)
const confirmRestore = ref(false)

async function exportBackup() {
  backupBusy.value = 'export'
  try {
    const blob = await backupApi.export()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `contaslt-dados-${new Date().toISOString().slice(0, 10)}.zip`
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 60_000)
    notifySuccess('Arquivo gerado')
  } catch (err) {
    notifyError(err)
  } finally {
    backupBusy.value = null
  }
}

function pickBackup(e: Event) {
  const input = e.target as HTMLInputElement
  backupFile.value = input.files?.[0] ?? null
  input.value = ''
  if (backupFile.value) confirmRestore.value = true
}

async function restoreBackup() {
  const file = backupFile.value
  if (!file) return
  backupBusy.value = 'import'
  try {
    const r = await backupApi.import(file)
    await queryClient.invalidateQueries()
    await catalog.load(true)
    ui.dataChanged()
    const parts = [`${r.transactions} ${r.transactions === 1 ? 'lançamento' : 'lançamentos'}`]
    if (r.accounts) parts.push(`${r.accounts} ${r.accounts === 1 ? 'conta' : 'contas'}`)
    if (r.categories) parts.push(`${r.categories} ${r.categories === 1 ? 'categoria' : 'categorias'}`)
    if (r.recurringBills) parts.push(`${r.recurringBills} ${r.recurringBills === 1 ? 'recorrente' : 'recorrentes'}`)
    if (r.receipts) parts.push(`${r.receipts} ${r.receipts === 1 ? 'comprovante' : 'comprovantes'}`)
    notifySuccess(`Importados: ${parts.join(', ')}` + (r.skippedTransactions ? ` (${r.skippedTransactions} já existiam)` : ''))
  } catch (err) {
    notifyError(err)
  } finally {
    backupBusy.value = null
    backupFile.value = null
  }
}

async function addRule() {
  if (!keyword.value.trim() || !ruleCategoryId.value) return
  saving.value = true
  try {
    await rulesApi.create(keyword.value, ruleCategoryId.value)
    keyword.value = ''
    notifySuccess('Regra salva')
    loadRules()
  } catch (err) {
    notifyError(err)
  } finally {
    saving.value = false
  }
}

async function removeRule(r: CategoryRule) {
  try {
    await rulesApi.remove(r.id)
    void loadRules()
  } catch (err) {
    notifyError(err)
  }
}

async function logout() {
  await auth.logout()
  catalog.reset()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="space-y-8">
    <section class="flex items-center gap-3 rounded-2xl border bg-card p-4">
      <span class="inline-flex size-12 items-center justify-center rounded-full bg-primary text-lg font-bold text-primary-foreground">
        {{ (auth.user?.name ?? '?').charAt(0).toUpperCase() }}
      </span>
      <span class="min-w-0 flex-1">
        <span class="block truncate font-bold">{{ auth.user?.name }}</span>
        <span class="block truncate text-sm text-muted-foreground">{{ auth.user?.email }}</span>
      </span>
      <Button variant="outline" size="sm" @click="logout"><LogOut /> Sair</Button>
    </section>

    <section>
      <SectionTitle title="Aparência" />
      <div role="radiogroup" class="grid grid-cols-3 gap-2">
        <button
          v-for="t in themes" :key="t.value" type="button" role="radio" :aria-checked="theme.preference === t.value"
          :class="cn('flex flex-col items-center gap-1.5 rounded-xl border bg-card px-3 py-3 text-sm font-semibold transition-colors',
            theme.preference === t.value ? 'border-primary text-primary ring-2 ring-primary/30' : 'text-muted-foreground hover:bg-accent')"
          @click="theme.set(t.value)"
        >
          <component :is="t.icon" class="size-5" />
          {{ t.label }}
        </button>
      </div>
    </section>

    <section class="md:hidden">
      <SectionTitle title="Relatórios" />
      <ul class="divide-y rounded-2xl border bg-card px-4">
        <li>
          <RouterLink :to="{ name: 'category-spending' }" class="flex items-center gap-3 py-3 font-semibold">
            <ChartPie class="size-[18px] text-muted-foreground" /> <span class="flex-1">Gastos por categoria</span> <ChevronRight class="size-4 text-muted-foreground" />
          </RouterLink>
        </li>
        <li>
          <RouterLink :to="{ name: 'item-spending' }" class="flex items-center gap-3 py-3 font-semibold">
            <ShoppingBasket class="size-[18px] text-muted-foreground" /> <span class="flex-1">Gasto por itens</span> <ChevronRight class="size-4 text-muted-foreground" />
          </RouterLink>
        </li>
      </ul>
    </section>

    <section class="md:hidden">
      <SectionTitle title="Organização" />
      <ul class="divide-y rounded-2xl border bg-card px-4">
        <li>
          <RouterLink :to="{ name: 'categories' }" class="flex items-center gap-3 py-3 font-semibold">
            <Tag class="size-[18px] text-muted-foreground" /> <span class="flex-1">Categorias</span> <ChevronRight class="size-4 text-muted-foreground" />
          </RouterLink>
        </li>
        <li>
          <RouterLink :to="{ name: 'accounts' }" class="flex items-center gap-3 py-3 font-semibold">
            <Wallet class="size-[18px] text-muted-foreground" /> <span class="flex-1">Contas</span> <ChevronRight class="size-4 text-muted-foreground" />
          </RouterLink>
        </li>
      </ul>
    </section>

    <section v-if="auth.user?.premium">
      <SectionTitle title="Exportar e importar" hint="Um .zip com os seus dados: contas, categorias, regras, recorrentes, lançamentos com itens e as fotos dos comprovantes." />
      <div class="flex flex-wrap gap-2 rounded-2xl border bg-card p-3">
        <Button variant="outline" :disabled="backupBusy !== null" @click="exportBackup">
          <Download /> {{ backupBusy === 'export' ? 'Gerando…' : 'Exportar dados' }}
        </Button>
        <input ref="backupInput" type="file" accept=".zip,application/zip" class="hidden" @change="pickBackup">
        <Button variant="outline" :disabled="backupBusy !== null" @click="backupInput?.click()">
          <Upload /> {{ backupBusy === 'import' ? 'Importando…' : 'Importar dados' }}
        </Button>
        <p class="basis-full text-xs text-muted-foreground">Importar soma ao que já existe: contas e categorias são casadas pelo nome, e lançamentos iguais são ignorados.</p>
      </div>
      <ConfirmDialog
        v-model:open="confirmRestore" title="Importar este arquivo?"
        :description="`Os dados de ${backupFile?.name ?? 'arquivo'} serão somados aos desta conta. Nada do que já existe é apagado; lançamentos iguais não entram de novo.`"
        confirm-label="Importar" @confirm="restoreBackup"
      />
    </section>

    <section>
      <SectionTitle title="Regras de categorização" hint="Se a descrição contiver a palavra, a categoria é sugerida na importação." />
      <form class="grid gap-2 rounded-2xl border bg-card p-3 md:grid-cols-[1fr_auto_14rem_auto]" @submit.prevent="addRule">
        <Input v-model="keyword" placeholder="Palavra-chave, ex.: ifood" maxlength="120" />
        <div role="radiogroup" class="grid grid-cols-2 gap-1 rounded-lg bg-muted p-1">
          <button
            v-for="t in (['EXPENSE', 'INCOME'] as const)" :key="t" type="button" role="radio" :aria-checked="ruleType === t"
            :class="cn('h-7 rounded-md px-3 text-xs font-semibold', ruleType === t ? 'bg-card shadow-xs' : 'text-muted-foreground')"
            @click="ruleType = t; ruleCategoryId = null"
          >
            {{ t === 'EXPENSE' ? 'Despesa' : 'Receita' }}
          </button>
        </div>
        <CategorySelect v-model="ruleCategoryId" :type="ruleType" :allow-none="false" />
        <Button type="submit" :disabled="saving || !keyword.trim() || !ruleCategoryId"><Plus :stroke-width="2.5" /> Adicionar</Button>
      </form>
      <ul v-if="rules.length" class="mt-2 divide-y rounded-2xl border bg-card px-4">
        <li v-for="r in rules" :key="r.id" class="flex items-center gap-3 py-2.5 text-sm">
          <span class="min-w-0 flex-1 truncate font-semibold">“{{ r.keyword }}”</span>
          <span class="flex items-center gap-2 text-muted-foreground">
            <CategoryIcon :icon="r.categoryIcon" :color="r.categoryColor" size="sm" /> {{ r.categoryName }}
          </span>
          <Button variant="ghost" size="icon-sm" class="text-muted-foreground hover:text-destructive" title="Remover" aria-label="Remover" @click="removeRule(r)">
            <Trash2 />
          </Button>
        </li>
      </ul>
      <p v-else class="mt-2 text-sm text-muted-foreground">Nenhuma regra ainda. Elas também são criadas automaticamente quando você categoriza uma importação.</p>
    </section>
  </div>
</template>
