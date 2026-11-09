<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Eye, EyeOff } from 'lucide-vue-next'
import { authApi } from '@/api'
import FormField from '@/components/app/FormField.vue'
import GoogleButton from '@/components/app/GoogleButton.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { fieldErrors, notifyError, notifySuccess } from '@/lib/notify'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const mode = ref<'login' | 'register'>('login')
/** Recuperação de senha: pede o e-mail, depois o código de 6 dígitos e a nova senha. */
const forgot = ref<null | 'email' | 'code'>(null)
const form = reactive({ name: '', email: '', password: '', code: '' })
const errors = ref<Record<string, string>>({})
const showPassword = ref(false)
const busy = ref(false)

function done() {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  router.replace(redirect)
}

async function run(fn: () => Promise<void>) {
  errors.value = {}
  busy.value = true
  try {
    await fn()
  } catch (err) {
    errors.value = fieldErrors(err)
    if (Object.keys(errors.value).length === 0) notifyError(err)
  } finally {
    busy.value = false
  }
}

function submit() {
  return run(async () => {
    if (mode.value === 'login') await auth.login(form.email, form.password)
    else await auth.register(form.name, form.email, form.password)
    done()
  })
}

function google(idToken: string) {
  return run(async () => {
    await auth.loginWithGoogle(idToken)
    done()
  })
}

function startForgot() {
  errors.value = {}
  form.code = ''
  form.password = ''
  forgot.value = 'email'
}

function sendCode() {
  return run(async () => {
    await authApi.forgotPassword(form.email.trim())
    form.code = ''
    form.password = ''
    forgot.value = 'code'
  })
}

function resetPassword() {
  return run(async () => {
    await auth.resetPassword(form.email.trim(), form.code, form.password)
    notifySuccess('Senha alterada')
    done()
  })
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
        <template v-if="forgot">
          <button type="button" class="mb-4 flex items-center gap-1 text-sm font-semibold text-muted-foreground hover:text-foreground" @click="forgot = null; errors = {}">
            <ArrowLeft class="size-4" /> Voltar para o login
          </button>
          <h2 class="text-xl font-bold tracking-tight">Recuperar senha</h2>

          <form v-if="forgot === 'email'" class="mt-4 space-y-4" @submit.prevent="sendCode">
            <p class="text-sm text-muted-foreground">Informe o e-mail da sua conta. Vamos mandar um código de 6 dígitos para criar uma nova senha.</p>
            <FormField label="E-mail" for="forgot-email" :error="errors.email">
              <Input id="forgot-email" v-model="form.email" type="email" autocomplete="email" inputmode="email" placeholder="voce@exemplo.com" />
            </FormField>
            <Button type="submit" class="h-11 w-full text-base font-semibold" :disabled="busy">
              {{ busy ? 'Enviando…' : 'Enviar código' }}
            </Button>
          </form>

          <form v-else class="mt-4 space-y-4" @submit.prevent="resetPassword">
            <p class="text-sm text-muted-foreground">
              Se existir uma conta com <strong class="text-foreground">{{ form.email.trim() }}</strong>, o código chega em instantes.
              Confira também o spam. Ele vale por 15 minutos.
            </p>
            <FormField label="Código" for="reset-code" :error="errors.code">
              <Input id="reset-code" v-model="form.code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="000000" class="text-center text-lg tracking-[0.4em]" />
            </FormField>
            <FormField label="Nova senha" for="reset-password" :error="errors.password" hint="Pelo menos 6 caracteres">
              <Input id="reset-password" v-model="form.password" type="password" autocomplete="new-password" />
            </FormField>
            <Button type="submit" class="h-11 w-full text-base font-semibold" :disabled="busy">
              {{ busy ? 'Aguarde…' : 'Salvar nova senha' }}
            </Button>
            <button type="button" class="w-full text-center text-sm font-semibold text-primary" :disabled="busy" @click="sendCode">
              Mandar outro código
            </button>
          </form>
        </template>

        <template v-else>
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
          <div v-if="mode === 'login'" class="-mt-2 text-right">
            <button type="button" class="text-xs font-semibold text-primary" @click="startForgot">Esqueci minha senha</button>
          </div>
          <Button type="submit" class="h-11 w-full text-base font-semibold" :disabled="busy">
            {{ busy ? 'Aguarde…' : mode === 'login' ? 'Entrar' : 'Criar minha conta' }}
          </Button>
        </form>

        <GoogleButton class="mt-6" divider @token="google" />
        </template>
      </div>
    </section>
  </div>
</template>
