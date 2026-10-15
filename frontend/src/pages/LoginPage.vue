<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Eye, EyeOff } from 'lucide-vue-next'
import FormField from '@/components/app/FormField.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { fieldErrors, notifyError } from '@/lib/notify'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const mode = ref<'login' | 'register'>('login')
const form = reactive({ name: '', email: '', password: '' })
const errors = ref<Record<string, string>>({})
const showPassword = ref(false)
const busy = ref(false)

async function submit() {
  errors.value = {}
  busy.value = true
  try {
    if (mode.value === 'login') await auth.login(form.email, form.password)
    else await auth.register(form.name, form.email, form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    router.replace(redirect)
  } catch (err) {
    errors.value = fieldErrors(err)
    if (Object.keys(errors.value).length === 0) notifyError(err)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="flex min-h-dvh flex-col md:flex-row">
    <section class="relative flex flex-col justify-between bg-[#0f1f18] px-6 py-8 text-[#e6eee9] md:w-[46%] md:px-12 md:py-12">
      <div class="flex items-center gap-2.5">
        <img src="/favicon.svg" alt="" class="size-9 rounded-lg" />
        <span class="text-lg font-extrabold tracking-tight">ContasLT</span>
      </div>
      <div class="my-10 md:my-0">
        <h1 class="max-w-md text-3xl font-extrabold leading-tight tracking-tight md:text-4xl">
          Suas contas do mês, sem surpresa no vencimento.
        </h1>
        <p class="mt-4 max-w-sm text-[#94a79c]">
          Contas recorrentes organizadas, categorias que aprendem com você e importação do extrato do banco em segundos.
        </p>
      </div>
      <ul class="hidden gap-6 text-sm text-[#94a79c] md:flex">
        <li>Recorrências e parcelas</li>
        <li>OFX e Excel</li>
        <li>Funciona offline</li>
      </ul>
    </section>

    <section class="flex flex-1 items-center justify-center px-6 py-10">
      <div class="w-full max-w-sm">
        <div role="tablist" class="mb-6 grid grid-cols-2 gap-1 rounded-lg bg-muted p-1">
          <button
            v-for="m in (['login', 'register'] as const)"
            :key="m"
            type="button"
            role="tab"
            :aria-selected="mode === m"
            :class="cn('h-9 rounded-md text-sm font-semibold transition-colors', mode === m ? 'bg-card shadow-xs' : 'text-muted-foreground hover:text-foreground')"
            @click="mode = m; errors = {}"
          >
            {{ m === 'login' ? 'Entrar' : 'Criar conta' }}
          </button>
        </div>

        <form class="space-y-4" @submit.prevent="submit">
          <FormField v-if="mode === 'register'" label="Nome" for="name" :error="errors.name">
            <Input id="name" v-model="form.name" autocomplete="name" placeholder="Como quer ser chamado" />
          </FormField>
          <FormField label="E-mail" for="email" :error="errors.email">
            <Input id="email" v-model="form.email" type="email" autocomplete="email" inputmode="email" placeholder="voce@exemplo.com" />
          </FormField>
          <FormField label="Senha" for="password" :error="errors.password" :hint="mode === 'register' ? 'Pelo menos 6 caracteres' : undefined">
            <div class="relative">
              <Input id="password" v-model="form.password" :type="showPassword ? 'text' : 'password'" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" class="pr-10" />
              <button type="button" class="absolute inset-y-0 right-0 flex w-10 items-center justify-center text-muted-foreground hover:text-foreground" :aria-label="showPassword ? 'Ocultar senha' : 'Mostrar senha'" @click="showPassword = !showPassword">
                <component :is="showPassword ? EyeOff : Eye" class="size-4" />
              </button>
            </div>
          </FormField>
          <Button type="submit" class="h-11 w-full text-base font-semibold" :disabled="busy">
            {{ busy ? 'Aguarde…' : mode === 'login' ? 'Entrar' : 'Criar minha conta' }}
          </Button>
        </form>
      </div>
    </section>
  </div>
</template>
