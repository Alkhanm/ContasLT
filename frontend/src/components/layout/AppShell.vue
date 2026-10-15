<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { CalendarDays, Ellipsis, FileUp, LayoutDashboard, ListChecks, LogOut, Plus, Repeat, Tag, Wallet } from 'lucide-vue-next'
import MonthNavigator from './MonthNavigator.vue'
import TransactionFormSheet from '@/components/app/TransactionFormSheet.vue'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import { useUiStore } from '@/stores/ui'
import { cn } from '@/lib/utils'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const catalog = useCatalogStore()
const ui = useUiStore()

const mainNav = [
  { name: 'home', label: 'Início', icon: LayoutDashboard },
  { name: 'transactions', label: 'Lançamentos', icon: ListChecks },
  { name: 'recurring', label: 'Recorrentes', icon: Repeat },
  { name: 'import', label: 'Importar', icon: FileUp },
]
const secondaryNav = [
  { name: 'categories', label: 'Categorias', icon: Tag },
  { name: 'accounts', label: 'Contas', icon: Wallet },
  { name: 'settings', label: 'Mais', icon: Ellipsis },
]
const mobileNav = [...mainNav.slice(0, 2), { name: 'recurring', label: 'Recorrentes', icon: CalendarDays }, mainNav[3]!, secondaryNav[2]!]

function isActive(name: string) {
  return route.name === name
}

function logout() {
  auth.logout()
  catalog.reset()
  router.push({ name: 'login' })
}

onMounted(() => {
  auth.loadUser()
  catalog.load()
})
</script>

<template>
  <div class="flex min-h-dvh">
    <aside class="sticky top-0 hidden h-dvh w-60 shrink-0 flex-col border-r bg-sidebar md:flex">
      <RouterLink :to="{ name: 'home' }" class="flex items-center gap-2.5 px-5 pt-6 pb-5">
        <img src="/favicon.svg" alt="" class="size-8 rounded-lg" />
        <span class="text-lg font-extrabold tracking-tight">ContasLT</span>
      </RouterLink>
      <nav class="flex flex-1 flex-col gap-0.5 px-3" aria-label="Principal">
        <RouterLink
          v-for="item in [...mainNav, ...secondaryNav]"
          :key="item.name"
          :to="{ name: item.name }"
          :class="cn('flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-semibold transition-colors',
            isActive(item.name) ? 'bg-primary/12 text-primary' : 'text-muted-foreground hover:bg-accent hover:text-foreground')"
        >
          <component :is="item.icon" class="size-[18px]" />
          {{ item.label }}
        </RouterLink>
      </nav>
      <div class="border-t px-3 py-3">
        <div class="flex items-center gap-3 px-2 py-1">
          <span class="inline-flex size-8 items-center justify-center rounded-full bg-primary text-sm font-bold text-primary-foreground">
            {{ (auth.user?.name ?? '?').charAt(0).toUpperCase() }}
          </span>
          <span class="min-w-0 flex-1">
            <span class="block truncate text-sm font-semibold">{{ auth.user?.name ?? '…' }}</span>
            <span class="block truncate text-xs text-muted-foreground">{{ auth.user?.email }}</span>
          </span>
          <button type="button" class="inline-flex size-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground" title="Sair" aria-label="Sair" @click="logout">
            <LogOut class="size-4" />
          </button>
        </div>
      </div>
    </aside>

    <div class="flex min-w-0 flex-1 flex-col">
      <header class="sticky top-0 z-30 border-b bg-background/85 backdrop-blur supports-[backdrop-filter]:bg-background/70">
        <div class="mx-auto flex h-14 w-full max-w-5xl items-center gap-3 px-4">
          <h1 class="text-lg font-bold tracking-tight md:text-xl">{{ route.meta.title }}</h1>
          <div class="ml-auto flex items-center gap-2">
            <MonthNavigator v-if="route.meta.monthNav" />
            <button
              type="button"
              class="hidden h-9 items-center gap-2 rounded-full bg-primary px-4 text-sm font-semibold text-primary-foreground shadow-sm hover:bg-primary/90 md:inline-flex"
              @click="ui.newTransaction()"
            >
              <Plus class="size-4" :stroke-width="2.5" />
              Novo lançamento
            </button>
          </div>
        </div>
      </header>

      <main class="mx-auto w-full max-w-5xl flex-1 px-4 pt-4 pb-28 md:pb-10">
        <RouterView />
      </main>
    </div>

    <button
      type="button"
      class="fixed right-4 bottom-[calc(4.25rem+env(safe-area-inset-bottom))] z-40 inline-flex size-14 items-center justify-center rounded-full bg-primary text-primary-foreground shadow-lg shadow-primary/30 transition-transform active:scale-95 md:hidden"
      aria-label="Novo lançamento"
      @click="ui.newTransaction()"
    >
      <Plus class="size-6" :stroke-width="2.5" />
    </button>

    <nav class="fixed inset-x-0 bottom-0 z-30 border-t bg-background/95 backdrop-blur safe-bottom md:hidden" aria-label="Principal">
      <ul class="grid grid-cols-5">
        <li v-for="item in mobileNav" :key="item.name">
          <RouterLink
            :to="{ name: item.name }"
            :class="cn('flex flex-col items-center gap-1 py-2 text-[11px] font-semibold',
              isActive(item.name) ? 'text-primary' : 'text-muted-foreground')"
          >
            <component :is="item.icon" class="size-5" :stroke-width="isActive(item.name) ? 2.5 : 2" />
            {{ item.label }}
          </RouterLink>
        </li>
      </ul>
    </nav>

    <TransactionFormSheet />
  </div>
</template>
