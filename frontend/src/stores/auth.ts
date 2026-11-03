import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api'
import { tokenStorage } from '@/api/client'
import type { AuthResponse, User } from '@/api/types'
import { clearPersistedCache, queryClient } from '@/lib/queryClient'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(tokenStorage.get())
  const user = ref<User | null>(null)
  const isAuthenticated = computed(() => token.value !== null)

  function apply(res: AuthResponse) {
    token.value = res.token
    tokenStorage.set(res.token)
    user.value = res.user
  }

  async function login(email: string, password: string) {
    apply(await authApi.login(email, password))
  }

  async function register(name: string, email: string, password: string) {
    apply(await authApi.register(name, email, password))
  }

  async function loadUser() {
    if (!token.value || user.value) return
    try {
      // Pelo cache: abrir a PWA não precisa esperar o servidor só para saber o nome do usuário. Um token
      // inválido é descoberto na primeira chamada real (401 dispara o evento contaslt:unauthorized).
      user.value = await queryClient.fetchQuery({ queryKey: ['me'], queryFn: () => authApi.me(), staleTime: 10 * 60_000 })
    } catch {
      // 401 já é tratado pelo cliente (evento contaslt:unauthorized)
    }
  }

  function logout() {
    token.value = null
    user.value = null
    tokenStorage.set(null)
    void clearPersistedCache()
  }

  return { token, user, isAuthenticated, login, register, loadUser, logout }
})
