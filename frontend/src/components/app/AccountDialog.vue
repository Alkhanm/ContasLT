<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
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
const form = reactive({
  name: '', type: 'CHECKING' as AccountType, initialBalance: null as number | null, color: colorPalette[0]!,
  closingDay: null as number | null, dueDay: null as number | null,
})
const isCard = computed(() => form.type === 'CREDIT_CARD')

function dayInput(value: string): number | null {
  const n = Number(value)
  return value === '' || !Number.isFinite(n) ? null : Math.min(28, Math.max(1, Math.trunc(n)))
}
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
  form.closingDay = props.account?.closingDay ?? null
  form.dueDay = props.account?.dueDay ?? null
})

async function save() {
  const e: Record<string, string> = {}
  if (!form.name.trim()) e.name = 'Informe o nome'
  if (isCard.value && (form.closingDay === null) !== (form.dueDay === null)) e.closingDay = 'Informe os dois dias, ou nenhum'
  if (isCard.value && form.closingDay !== null && form.closingDay === form.dueDay) e.dueDay = 'Precisa ser diferente do fechamento'
  errors.value = e
  if (Object.keys(e).length) return
  saving.value = true
  try {
    const input = {
      name: form.name.trim(), type: form.type, initialBalance: form.initialBalance ?? 0, color: form.color,
      closingDay: isCard.value ? form.closingDay : null, dueDay: isCard.value ? form.dueDay : null,
    }
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
        <DialogDescription class="sr-only">Nome, tipo, saldo inicial, cor e, para cartão, dias de fechamento e vencimento</DialogDescription>
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
        <div v-if="isCard" class="grid grid-cols-2 gap-3">
          <FormField label="Fecha no dia" for="acc-closing" :error="errors.closingDay">
            <Input id="acc-closing" type="number" inputmode="numeric" min="1" max="28" placeholder="Ex.: 25" :model-value="form.closingDay ?? ''" @update:model-value="form.closingDay = dayInput(String($event))" />
          </FormField>
          <FormField label="Vence no dia" for="acc-due" :error="errors.dueDay">
            <Input id="acc-due" type="number" inputmode="numeric" min="1" max="28" placeholder="Ex.: 5" :model-value="form.dueDay ?? ''" @update:model-value="form.dueDay = dayInput(String($event))" />
          </FormField>
          <p class="col-span-2 -mt-2 text-xs text-muted-foreground">
            Com os dois dias, os lançamentos do cartão podem ser vistos por fatura e o pagamento da fatura é reconhecido na importação do extrato.
          </p>
        </div>
        <FormField label="Saldo inicial" for="acc-balance" :hint="isCard ? 'Negativo se já havia fatura em aberto antes do primeiro lançamento registrado aqui.' : 'Saldo da conta antes do primeiro lançamento registrado aqui.'">
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
