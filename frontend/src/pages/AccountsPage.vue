<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Archive, ArchiveRestore, CreditCard, Plus, Trash2, Wallet } from 'lucide-vue-next'
import { accountsApi } from '@/api'
import type { Account } from '@/api/types'
import AccountDialog from '@/components/app/AccountDialog.vue'
import ConfirmDialog from '@/components/app/ConfirmDialog.vue'
import Money from '@/components/app/Money.vue'
import { Button } from '@/components/ui/button'
import { accountTypeLabels } from '@/lib/format'
import { notifyError, notifySuccess } from '@/lib/notify'
import { cn } from '@/lib/utils'
import { useCatalogStore } from '@/stores/catalog'
import { useUiStore } from '@/stores/ui'

const catalog = useCatalogStore()
const ui = useUiStore()
const dialogOpen = ref(false)
const editing = ref<Account | null>(null)
const toDelete = ref<Account | null>(null)

onMounted(() => catalog.load(true))

const total = computed(() => catalog.activeAccounts.reduce((s, a) => s + a.balance, 0))

function openNew() {
  editing.value = null
  dialogOpen.value = true
}
function openEdit(a: Account) {
  editing.value = a
  dialogOpen.value = true
}

async function toggleArchive(a: Account) {
  try {
    await accountsApi.update(a.id, { name: a.name, type: a.type, initialBalance: a.initialBalance, color: a.color, archived: !a.archived, closingDay: a.closingDay, dueDay: a.dueDay })
    await catalog.reloadAccounts()
    notifySuccess(a.archived ? 'Conta restaurada' : 'Conta arquivada')
    ui.dataChanged()
  } catch (err) {
    notifyError(err)
  }
}

async function remove() {
  const target = toDelete.value
  if (!target) return
  try {
    await accountsApi.remove(target.id)
    await catalog.reloadAccounts()
    notifySuccess('Conta excluída')
  } catch (err) {
    notifyError(err)
  } finally {
    toDelete.value = null
  }
}
</script>

<template>
  <div class="space-y-4">
    <div class="flex items-center justify-between gap-3">
      <div>
        <p class="text-xs font-semibold text-muted-foreground">Saldo total</p>
        <Money :value="total" class="text-xl font-bold" />
      </div>
      <Button class="rounded-full" @click="openNew"><Plus :stroke-width="2.5" /> Nova conta</Button>
    </div>

    <ul class="grid gap-2 sm:grid-cols-2">
      <li v-for="a in catalog.accounts" :key="a.id" :class="cn('flex items-center gap-3 rounded-xl border bg-card px-3 py-2.5', a.archived && 'opacity-60')">
        <button type="button" class="flex min-w-0 flex-1 items-center gap-3 text-left outline-none rounded-md focus-visible:ring-3 focus-visible:ring-ring/50" @click="openEdit(a)">
          <span class="inline-flex size-10 shrink-0 items-center justify-center rounded-full" :style="{ backgroundColor: a.color + '22', color: a.color }">
            <component :is="a.type === 'CREDIT_CARD' ? CreditCard : Wallet" class="size-[18px]" />
          </span>
          <span class="min-w-0 flex-1">
            <span class="block truncate font-semibold">{{ a.name }}</span>
            <span class="block text-xs text-muted-foreground">{{ accountTypeLabels[a.type] }}<template v-if="a.type === 'CREDIT_CARD' && a.closingDay"> · fecha dia {{ a.closingDay }}, vence dia {{ a.dueDay }}</template><template v-if="a.archived"> · arquivada</template></span>
          </span>
          <Money :value="a.balance" class="text-sm font-semibold" />
        </button>
        <Button variant="ghost" size="icon-sm" :title="a.archived ? 'Restaurar' : 'Arquivar'" :aria-label="a.archived ? 'Restaurar' : 'Arquivar'" @click="toggleArchive(a)">
          <component :is="a.archived ? ArchiveRestore : Archive" />
        </Button>
        <Button variant="ghost" size="icon-sm" class="text-muted-foreground hover:text-destructive" title="Excluir" aria-label="Excluir" @click="toDelete = a">
          <Trash2 />
        </Button>
      </li>
    </ul>

    <AccountDialog v-model:open="dialogOpen" :account="editing" @saved="ui.dataChanged()" />
    <ConfirmDialog
      :open="toDelete !== null" title="Excluir conta?"
      description="Só é possível excluir contas sem lançamentos. Se ela já foi usada, arquive-a."
      confirm-label="Excluir" destructive @update:open="(v) => !v && (toDelete = null)" @confirm="remove"
    />
  </div>
</template>
