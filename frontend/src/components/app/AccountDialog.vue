<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { Check } from 'lucide-vue-next'
import { accountsApi } from '@/api'
import type { Account, AccountType } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import FormField from './FormField.vue'
import MoneyInput from './MoneyInput.vue'
import { accountTypeLabels } from '@/lib/format'
import { colorPalette } from '@/lib/icons'
import { fieldErrors, notifyError, notifySuccess } from '@/lib/notify'
import { useCatalogStore } from '@/stores/catalog'

const props = defineProps<{ open: boolean; account: Account | null }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; saved: [] }>()

const catalog = useCatalogStore()
const form = reactive({ name: '', type: 'CHECKING' as AccountType, initialBalance: null as number | null, color: colorPalette[0]! })
const errors = ref<Record<string, string>>({})
const saving = ref(false)
const types = Object.entries(accountTypeLabels) as [AccountType, string][]

watch(() => props.open, (open) => {
  if (!open) return
  errors.value = {}
  form.name = props.account?.name ?? ''
  form.type = props.account?.type ?? 'CHECKING'
  form.initialBalance = props.account?.initialBalance ?? null
  form.color = props.account?.color ?? colorPalette[0]!
})

async function save() {
  if (!form.name.trim()) {
    errors.value = { name: 'Informe o nome' }
    return
  }
  saving.value = true
  try {
    const input = { name: form.name.trim(), type: form.type, initialBalance: form.initialBalance ?? 0, color: form.color }
    if (props.account) await accountsApi.update(props.account.id, { ...input, archived: props.account.archived })
    else await accountsApi.create(input)
    await catalog.reloadAccounts()
    notifySuccess(props.account ? 'Conta atualizada' : 'Conta criada')
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
    <DialogContent class="sm:max-w-md">
      <DialogHeader>
        <DialogTitle>{{ account ? 'Editar conta' : 'Nova conta' }}</DialogTitle>
        <DialogDescription class="sr-only">Nome, tipo, saldo inicial e cor da conta</DialogDescription>
      </DialogHeader>
      <form class="space-y-4" @submit.prevent="save">
        <FormField label="Nome" for="acc-name" :error="errors.name">
          <Input id="acc-name" v-model="form.name" maxlength="80" placeholder="Ex.: Nubank" autocomplete="off" />
        </FormField>
        <FormField label="Tipo" for="acc-type">
          <Select v-model="form.type">
            <SelectTrigger id="acc-type" class="w-full"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem v-for="[value, label] in types" :key="value" :value="value">{{ label }}</SelectItem>
            </SelectContent>
          </Select>
        </FormField>
        <FormField label="Saldo inicial" for="acc-balance" hint="Saldo da conta antes do primeiro lançamento registrado aqui.">
          <MoneyInput id="acc-balance" v-model="form.initialBalance" />
        </FormField>
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
        <DialogFooter>
          <Button type="submit" class="w-full sm:w-auto" :disabled="saving">{{ saving ? 'Salvando…' : 'Salvar' }}</Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
