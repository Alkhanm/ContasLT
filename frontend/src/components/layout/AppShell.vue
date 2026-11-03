<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { CalendarDays, ChartPie, Ellipsis, FileUp, LayoutDashboard, ListChecks, LogOut, PanelLeftClose, PanelLeftOpen, Plus, Repeat, ShoppingBasket, Tag, Wallet } from 'lucide-vue-next'
import MonthNavigator from './MonthNavigator.vue'
import RecurringFormSheet from '@/components/app/RecurringFormSheet.vue'
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
  { name: 'category-spending', label: 'Gastos por categoria', icon: ChartPie },
  { name: 'item-spending', label: 'Gasto por itens', icon: ShoppingBasket },
  { name: 'categories', label: 'Categorias', icon: Tag },
  { name: 'accounts', label: 'Contas', icon: Wallet },
  { name: 'settings', label: 'Mais', icon: Ellipsis },
]
const mobileNav = [...mainNav.slice(0, 2), { name: 'recurring', label: 'Recorrentes', icon: CalendarDays }, mainNav[3]!, secondaryNav[secondaryNav.length - 1]!]

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
    <aside
      :class="cn('sticky top-0 hidden h-dvh shrink-0 flex-col border-r bg-sidebar transition-[width] duration-200 md:flex',
        ui.sidebarCollapsed ? 'w-16' : 'w-60')"
    >
      <div :class="cn('flex items-center pt-6 pb-5', ui.sidebarCollapsed ? 'flex-col gap-3 px-3' : 'gap-2.5 px-5')">
        <RouterLink :to="{ name: 'home' }" class="flex min-w-0 items-center gap-2.5" :title="ui.sidebarCollapsed ? 'ContasLT' : undefined">
          <img src="/favicon.svg" alt="" class="size-8 shrink-0 rounded-lg" />
          <span v-if="!ui.sidebarCollapsed" class="truncate text-lg font-extrabold tracking-tight">ContasLT</span>
        </RouterLink>
        <button
          type="button"
          :class="cn('inline-flex size-8 shrink-0 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground', !ui.sidebarCollapsed && 'ml-auto')"
          :title="ui.sidebarCollapsed ? 'Abrir menu' : 'Recolher menu'"
          :aria-label="ui.sidebarCollapsed ? 'Abrir menu' : 'Recolher menu'"
          :aria-expanded="!ui.sidebarCollapsed"
          @click="ui.toggleSidebar()"
        >
          <component :is="ui.sidebarCollapsed ? PanelLeftOpen : PanelLeftClose" class="size-[18px]" />
        </button>
      </div>
      <nav class="flex flex-1 flex-col gap-0.5 px-3" aria-label="Principal">
        <RouterLink
          v-for="item in [...mainNav, ...secondaryNav]"
          :key="item.name"
          :to="{ name: item.name }"
          :title="ui.sidebarCollapsed ? item.label : undefined"
          :class="cn('flex items-center gap-3 rounded-lg py-2 text-sm font-semibold transition-colors',
            ui.sidebarCollapsed ? 'justify-center px-0' : 'px-3',
            isActive(item.name) ? 'bg-primary/12 text-primary' : 'text-muted-foreground hover:bg-accent hover:text-foreground')"
        >
          <component :is="item.icon" class="size-[18px] shrink-0" />
          <span v-if="!ui.sidebarCollapsed" class="truncate">{{ item.label }}</span>
          <span v-else class="sr-only">{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="border-t px-3 py-3">
        <div :class="cn('flex items-center gap-3 py-1', ui.sidebarCollapsed ? 'flex-col px-0' : 'px-2')">
          <span class="inline-flex size-8 shrink-0 items-center justify-center rounded-full bg-primary text-sm font-bold text-primary-foreground" :title="ui.sidebarCollapsed ? auth.user?.name : undefined">
            {{ (auth.user?.name ?? '?').charAt(0).toUpperCase() }}
          </span>
          <span v-if="!ui.sidebarCollapsed" class="min-w-0 flex-1">
            <span class="block truncate text-sm font-semibold">{{ auth.user?.name ?? '…' }}</span>
            <span class="block truncate text-xs text-muted-foreground">{{ auth.user?.email }}</span>
          </span>
          <button type="button" class="inline-flex size-8 shrink-0 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground" title="Sair" aria-label="Sair" @click="logout">
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
    <RecurringFormSheet
      :open="ui.recurringFromTransaction !== null" :bill="null" :from-transaction="ui.recurringFromTransaction"
      @update:open="(v) => !v && ui.closeRecurringForm()" @saved="ui.dataChanged()"
    />
  </div>
</template>
