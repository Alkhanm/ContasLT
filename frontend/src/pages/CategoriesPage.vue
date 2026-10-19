<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Archive, ArchiveRestore, Plus, Trash2 } from 'lucide-vue-next'
import { categoriesApi } from '@/api'
import type { Category, TransactionType } from '@/api/types'
import CategoryDialog from '@/components/app/CategoryDialog.vue'
import CategoryIcon from '@/components/app/CategoryIcon.vue'
import ConfirmDialog from '@/components/app/ConfirmDialog.vue'
import { Button } from '@/components/ui/button'
import { notifyError, notifySuccess } from '@/lib/notify'
import { cn } from '@/lib/utils'
import { useCatalogStore } from '@/stores/catalog'
import { useUiStore } from '@/stores/ui'

const catalog = useCatalogStore()
const ui = useUiStore()
const tab = ref<TransactionType>('EXPENSE')
const dialogOpen = ref(false)
const editing = ref<Category | null>(null)
const toDelete = ref<Category | null>(null)
const showArchived = ref(false)

onMounted(() => catalog.load())

const list = computed(() => catalog.categories.filter((c) => c.type === tab.value && (showArchived.value || !c.archived)))
const archivedCount = computed(() => catalog.categories.filter((c) => c.type === tab.value && c.archived).length)

function openNew() {
  editing.value = null
  dialogOpen.value = true
}
function openEdit(c: Category) {
  editing.value = c
  dialogOpen.value = true
}

async function toggleArchive(c: Category) {
  try {
    await categoriesApi.update(c.id, { name: c.name, type: c.type, color: c.color, icon: c.icon, archived: !c.archived })
    await catalog.reloadCategories()
    notifySuccess(c.archived ? 'Categoria restaurada' : 'Categoria arquivada')
    ui.dataChanged()
  } catch (err) {
    notifyError(err)
  }
}

async function remove() {
  const target = toDelete.value
  if (!target) return
  try {
    await categoriesApi.remove(target.id)
    await catalog.reloadCategories()
    notifySuccess('Categoria excluída')
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
      <div role="tablist" class="grid w-56 grid-cols-2 gap-1 rounded-lg bg-muted p-1">
        <button
          v-for="t in (['EXPENSE', 'INCOME'] as const)" :key="t" type="button" role="tab" :aria-selected="tab === t"
          :class="cn('h-8 rounded-md text-xs font-semibold transition-colors', tab === t ? 'bg-card shadow-xs' : 'text-muted-foreground hover:text-foreground')"
          @click="tab = t"
        >
          {{ t === 'EXPENSE' ? 'Despesas' : 'Receitas' }}
        </button>
      </div>
      <Button class="rounded-full" @click="openNew"><Plus :stroke-width="2.5" /> Nova</Button>
    </div>

    <ul class="grid gap-2 sm:grid-cols-2">
      <li v-for="c in list" :key="c.id" :class="cn('flex items-center gap-3 rounded-xl border bg-card px-3 py-2.5', c.archived && 'opacity-60')">
        <button type="button" class="flex min-w-0 flex-1 items-center gap-3 text-left outline-none rounded-md focus-visible:ring-3 focus-visible:ring-ring/50" @click="openEdit(c)">
          <CategoryIcon :icon="c.icon" :color="c.color" />
          <span class="truncate font-semibold">{{ c.name }}</span>
          <span v-if="c.archived" class="text-xs text-muted-foreground">arquivada</span>
        </button>
        <Button variant="ghost" size="icon-sm" :title="c.archived ? 'Restaurar' : 'Arquivar'" :aria-label="c.archived ? 'Restaurar' : 'Arquivar'" @click="toggleArchive(c)">
          <component :is="c.archived ? ArchiveRestore : Archive" />
        </Button>
        <Button variant="ghost" size="icon-sm" class="text-muted-foreground hover:text-destructive" title="Excluir" aria-label="Excluir" @click="toDelete = c">
          <Trash2 />
        </Button>
      </li>
    </ul>

    <button v-if="archivedCount" type="button" class="text-xs font-semibold text-muted-foreground hover:text-foreground" @click="showArchived = !showArchived">
      {{ showArchived ? 'Ocultar arquivadas' : `Mostrar ${archivedCount} ${archivedCount === 1 ? 'arquivada' : 'arquivadas'}` }}
    </button>

    <CategoryDialog v-model:open="dialogOpen" :category="editing" :default-type="tab" />
    <ConfirmDialog
      :open="toDelete !== null" title="Excluir categoria?"
      description="Só é possível excluir categorias sem lançamentos. Se ela já foi usada, arquive-a."
      confirm-label="Excluir" destructive @update:open="(v) => !v && (toDelete = null)" @confirm="remove"
    />
  </div>
</template>
