<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { Check } from 'lucide-vue-next'
import { categoriesApi } from '@/api'
import type { Category, TransactionType } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import CategoryIcon from './CategoryIcon.vue'
import FormField from './FormField.vue'
import TypeToggle from './TypeToggle.vue'
import { colorPalette, iconFor, iconNames } from '@/lib/icons'
import { fieldErrors, notifyError, notifySuccess } from '@/lib/notify'
import { cn } from '@/lib/utils'
import { useCatalogStore } from '@/stores/catalog'

const props = defineProps<{ open: boolean; category: Category | null; defaultType?: TransactionType }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; saved: [] }>()

const catalog = useCatalogStore()
const form = reactive({ name: '', type: 'EXPENSE' as TransactionType, color: colorPalette[0]!, icon: 'tag' })
const errors = ref<Record<string, string>>({})
const saving = ref(false)

watch(() => props.open, (open) => {
  if (!open) return
  errors.value = {}
  form.name = props.category?.name ?? ''
  form.type = props.category?.type ?? props.defaultType ?? 'EXPENSE'
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
    const input = { name: form.name.trim(), type: form.type, color: form.color, icon: form.icon }
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
        <TypeToggle v-model="form.type" :disabled="!!category" />
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
