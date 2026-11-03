import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { VueQueryPlugin } from '@tanstack/vue-query'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'
import { useCatalogStore } from './stores/catalog'
import { useThemeStore } from './stores/theme'
import { queryClient } from './lib/queryClient'
import './assets/index.css'
import 'vue-sonner/style.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(VueQueryPlugin, { queryClient })

useThemeStore()

window.addEventListener('contaslt:unauthorized', () => {
  useAuthStore().logout()
  useCatalogStore().reset()
  router.push({ name: 'login' })
})

app.mount('#app')
