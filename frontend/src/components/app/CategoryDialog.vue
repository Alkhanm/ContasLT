<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Check } from 'lucide-vue-next'
import { categoriesApi } from '@/api'
import type { Category, TransactionType } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import CategoryIcon from './CategoryIcon.vue'
import FormField from './FormField.vue'
import TypeToggle from './TypeToggle.vue'
import { colorPalette, iconFor, iconNames } from '@/lib/icons'
import { fieldErrors, notifyError, notifySuccess } from '@/lib/notify'
import { cn } from '@/lib/utils'
import { useCatalogStore } from '@/stores/catalog'

const props = defineProps<{
  open: boolean
  category: Category | null
  defaultType?: TransactionType
  /** Pré-seleciona a categoria pai ao criar uma subcategoria. */
  defaultParent?: Category | null
}>()
const emit = defineEmits<{ 'update:open': [value: boolean]; saved: [] }>()

const catalog = useCatalogStore()
const form = reactive({ name: '', type: 'EXPENSE' as TransactionType, color: colorPalette[0]!, icon: 'tag', parentId: null as number | null })

/** Uma categoria que já tem subcategorias não pode virar subcategoria (só há um nível). */
const hasChildren = computed(() => !!props.category && catalog.childrenOf(props.category.id, true).length > 0)
const parentOptions = computed(() => catalog.rootCategories.filter((c) => c.type === form.type && c.id !== props.category?.id))
const parent = computed(() => catalog.category(form.parentId))
const parentValue = computed(() => (form.parentId == null ? 'none' : String(form.parentId)))

function onParent(v: unknown) {
  form.parentId = v === 'none' || v == null ? null : Number(v)
  if (parent.value) form.type = parent.value.type
}
const errors = ref<Record<string, string>>({})
const saving = ref(false)

watch(() => props.open, (open) => {
  if (!open) return
  errors.value = {}
  form.name = props.category?.name ?? ''
  form.parentId = props.category?.parentId ?? props.defaultParent?.id ?? null
  form.type = props.category?.type ?? props.defaultParent?.type ?? props.defaultType ?? 'EXPENSE'
  form.color = props.category?.color ?? colorPalette[Math.floor(Math.random() * colorPalette.length)]!
  form.icon = props.category?.icon ?? 'tag'
})

async function save() {
  if (!form.name.trim()) {
    errors.value = { name: 'Informe o nome' }
    return
  }
  saving.value = true
  try {
    const input = { name: form.name.trim(), type: form.type, color: form.color, icon: form.icon, parentId: form.parentId }
    if (props.category) await categoriesApi.update(props.category.id, { ...input, archived: props.category.archived })
    else await categoriesApi.create(input)
    await catalog.reloadCategories()
    notifySuccess(props.category ? 'Categoria atualizada' : 'Categoria criada')
    emit('update:open', false)
    emit('saved')
  } catch (err) {
    errors.value = fieldErrors(err)
    notifyError(err)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Dialog :open="open" @update:open="emit('update:open', $event)">
    <DialogContent class="max-h-[92dvh] overflow-y-auto sm:max-w-md">
      <DialogHeader>
        <DialogTitle>{{ category ? 'Editar categoria' : 'Nova categoria' }}</DialogTitle>
        <DialogDescription class="sr-only">Nome, tipo, cor e ícone da categoria</DialogDescription>
      </DialogHeader>
      <form class="space-y-4" @submit.prevent="save">
        <div class="flex items-center gap-3">
          <CategoryIcon :icon="form.icon" :color="form.color" size="lg" />
          <FormField label="Nome" for="cat-name" :error="errors.name" class="flex-1">
            <Input id="cat-name" v-model="form.name" maxlength="60" placeholder="Ex.: Pets" autocomplete="off" />
          </FormField>
        </div>
        <TypeToggle v-model="form.type" :disabled="!!category || form.parentId != null" />
        <FormField v-if="!hasChildren" label="Subcategoria de" for="cat-parent" hint="Opcional. Ex.: Contas da casa › Energia">
          <Select :model-value="parentValue" @update:model-value="onParent">
            <SelectTrigger id="cat-parent" class="w-full">
              <SelectValue>
                <span v-if="parent" class="flex items-center gap-2">
                  <CategoryIcon :icon="parent.icon" :color="parent.color" size="sm" />
                  <span class="truncate">{{ parent.name }}</span>
                </span>
                <span v-else class="text-muted-foreground">Nenhuma (categoria principal)</span>
              </SelectValue>
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="none"><span class="text-muted-foreground">Nenhuma (categoria principal)</span></SelectItem>
              <SelectItem v-for="c in parentOptions" :key="c.id" :value="String(c.id)">
                <span class="flex items-center gap-2">
                  <CategoryIcon :icon="c.icon" :color="c.color" size="sm" />
                  {{ c.name }}
                </span>
              </SelectItem>
            </SelectContent>
          </Select>
        </FormField>
        <p v-else class="text-xs text-muted-foreground">Esta categoria tem subcategorias, por isso continua como principal.</p>
        <FormField label="Cor">
          <div class="grid grid-cols-8 gap-2">
            <button
              v-for="c in colorPalette"
              :key="c"
              type="button"
              :aria-label="c"
              :aria-pressed="form.color === c"
              class="flex aspect-square items-center justify-center rounded-full outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              :style="{ backgroundColor: c }"
              @click="form.color = c"
            >
              <Check v-if="form.color === c" class="size-4 text-white" :stroke-width="3" />
            </button>
          </div>
        </FormField>
        <FormField label="Ícone">
          <div class="grid grid-cols-8 gap-1.5">
            <button
              v-for="name in iconNames"
              :key="name"
              type="button"
              :aria-label="name"
              :aria-pressed="form.icon === name"
              :class="cn('flex aspect-square items-center justify-center rounded-lg border outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
                form.icon === name ? 'border-primary bg-primary/10 text-primary' : 'text-muted-foreground hover:bg-accent')"
              @click="form.icon = name"
            >
              <component :is="iconFor(name)" class="size-4" />
            </button>
          </div>
        </FormField>
        <DialogFooter>
          <Button type="submit" class="w-full sm:w-auto" :disabled="saving">{{ saving ? 'Salvando…' : 'Salvar' }}</Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
