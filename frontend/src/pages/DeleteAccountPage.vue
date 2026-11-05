<script setup lang="ts">
import { reactive, ref } from 'vue'
import { TriangleAlert } from 'lucide-vue-next'
import { api } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import FormField from '@/components/app/FormField.vue'
import { fieldErrors, notifyError } from '@/lib/notify'
import { wipeSession } from '@/data/session'
import { useAuthStore } from '@/stores/auth'

/**
 * Excluir a conta (exigência da Play Store: também pela web, sem precisar do app). Confirma com e-mail e senha e
 * apaga o usuário, os dados sincronizados e as fotos dos comprovantes no servidor.
 */
const auth = useAuthStore()
const form = reactive({ email: auth.user?.email ?? '', password: '', confirm: false })
const errors = ref<Record<string, string>>({})
const busy = ref(false)
const done = ref(false)

async function submit() {
  errors.value = {}
  if (!form.confirm) {
    errors.value = { confirm: 'Marque a confirmação para continuar' }
    return
  }
  busy.value = true
  try {
    await api<void>('/api/auth/delete-account', { method: 'POST', body: { email: form.email, password: form.password } })
    // Se esta conta estava aberta neste navegador, os dados locais também saem.
    if (auth.user && auth.user.email.toLowerCase() === form.email.trim().toLowerCase()) {
      const id = auth.user.id
      auth.expire()
      await wipeSession(id)
    }
    done.value = true
  } catch (err) {
    const status = (err as { status?: number }).status
    if (status === 401) errors.value = { password: 'E-mail ou senha inválidos' }
    else {
      errors.value = fieldErrors(err)
      if (!Object.keys(errors.value).length) notifyError(err)
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="flex min-h-dvh items-start justify-center bg-background px-6 py-12">
    <div class="w-full max-w-md space-y-6">
      <div class="flex items-center gap-2.5">
        <img src="/favicon.svg" alt="" class="size-9 rounded-lg" />
        <span class="text-lg font-extrabold tracking-tight">ContasLT</span>
      </div>

      <div v-if="done" class="space-y-3 rounded-2xl border bg-card p-5">
        <h1 class="text-xl font-bold tracking-tight">Conta excluída</h1>
        <p class="text-sm text-muted-foreground">
          Sua conta e todos os dados guardados no servidor (contas, categorias, lançamentos, recorrentes e fotos dos
          comprovantes) foram apagados. Se o app estiver instalado em algum aparelho, saia da conta nele para apagar
          também a cópia local.
        </p>
      </div>

      <template v-else>
        <div class="space-y-2">
          <h1 class="text-2xl font-extrabold tracking-tight">Excluir minha conta</h1>
          <p class="text-sm text-muted-foreground">
            Apaga a sua conta do ContasLT e todos os dados guardados no servidor: contas, categorias, regras,
            lançamentos, contas recorrentes e as fotos dos comprovantes. Não dá para desfazer. Se quiser guardar
            uma cópia antes, use <strong>Mais › Exportar dados</strong> no app.
          </p>
        </div>

        <form class="space-y-4 rounded-2xl border bg-card p-5" @submit.prevent="submit">
          <FormField label="E-mail" for="del-email" :error="errors.email">
            <Input id="del-email" v-model="form.email" type="email" autocomplete="email" />
          </FormField>
          <FormField label="Senha" for="del-password" :error="errors.password">
            <Input id="del-password" v-model="form.password" type="password" autocomplete="current-password" />
          </FormField>
          <label class="flex items-start gap-2 text-sm">
            <input v-model="form.confirm" type="checkbox" class="mt-1" />
            <span>Entendo que a exclusão é definitiva e apaga todos os meus dados.</span>
          </label>
          <p v-if="errors.confirm" class="text-xs text-destructive">{{ errors.confirm }}</p>
          <p class="flex items-start gap-2 rounded-lg border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive">
            <TriangleAlert class="mt-0.5 size-3.5 shrink-0" />
            A exclusão é imediata e permanente.
          </p>
          <Button type="submit" variant="destructive" class="h-11 w-full text-base font-semibold" :disabled="busy">
            {{ busy ? 'Excluindo…' : 'Excluir minha conta' }}
          </Button>
        </form>
      </template>
    </div>
  </div>
</template>
