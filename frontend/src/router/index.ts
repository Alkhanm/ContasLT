import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: '/entrar',
      name: 'login',
      component: () => import('@/pages/LoginPage.vue'),
      meta: { guest: true, title: 'Entrar' },
    },
    {
      path: '/',
      component: () => import('@/components/layout/AppShell.vue'),
      meta: { requiresAuth: true },
      children: [
        { path: '', name: 'home', component: () => import('@/pages/DashboardPage.vue'), meta: { title: 'Início', monthNav: true } },
        { path: 'lancamentos', name: 'transactions', component: () => import('@/pages/TransactionsPage.vue'), meta: { title: 'Lançamentos', monthNav: true } },
        { path: 'gastos/categorias', name: 'category-spending', component: () => import('@/pages/CategorySpendingPage.vue'), meta: { title: 'Gastos por categoria', monthNav: true } },
        { path: 'gastos/itens', name: 'item-spending', component: () => import('@/pages/ItemSpendingPage.vue'), meta: { title: 'Gasto por itens' } },
        { path: 'recorrentes', name: 'recurring', component: () => import('@/pages/RecurringPage.vue'), meta: { title: 'Contas recorrentes' } },
        { path: 'importar', name: 'import', component: () => import('@/pages/ImportPage.vue'), meta: { title: 'Importar extrato' } },
        { path: 'categorias', name: 'categories', component: () => import('@/pages/CategoriesPage.vue'), meta: { title: 'Categorias' } },
        { path: 'contas', name: 'accounts', component: () => import('@/pages/AccountsPage.vue'), meta: { title: 'Contas' } },
        { path: 'mais', name: 'settings', component: () => import('@/pages/SettingsPage.vue'), meta: { title: 'Mais' } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  if (to.meta.guest && auth.isAuthenticated) {
    return { name: 'home' }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · ContasLT` : 'ContasLT'
})

export default router
