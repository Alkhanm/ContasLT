import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api'
import { tokenStorage } from '@/api/client'
import type { AuthResponse, User } from '@/api/types'
import { closeSession, openSession, syncNow, syncState, wipeSession } from '@/data/session'
import { clearQueries } from '@/lib/queryClient'
import { seedDefaults } from '@/services/catalog'
import { db } from '@/services/context'

const USER_KEY = 'contaslt.user'

function readUser(): User | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as User) : null
  } catch {
    return null
  }
}

function writeUser(user: User | null) {
  try {
    if (user) localStorage.setItem(USER_KEY, JSON.stringify(user))
    else localStorage.removeItem(USER_KEY)
  } catch {
    // sem armazenamento: o usuário é buscado de novo no servidor
  }
}

/**
 * Login e sessão. O usuário fica guardado no aparelho junto com o token, para o app abrir offline direto nos
 * dados locais; o banco local de cada usuário é aberto por {@link ensureSession}.
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(tokenStorage.get())
  const user = ref<User | null>(token.value ? readUser() : null)
  const isAuthenticated = computed(() => token.value !== null)

  function apply(res: AuthResponse) {
    token.value = res.token
    tokenStorage.set(res.token)
    user.value = res.user
    writeUser(res.user)
  }

  async function login(email: string, password: string) {
    apply(await authApi.login(email, password))
    await ensureSession()
  }

  /** Conta nova: o aparelho cria as categorias padrão e a conta principal (antes feito pelo servidor). */
  async function register(name: string, email: string, password: string) {
    apply(await authApi.register(name, email, password))
    await ensureSession()
    try {
      await syncNow()
    } catch {
      // sem rede agora: as categorias padrão sobem na próxima sincronização
    }
    if (db().isEmpty()) {
      seedDefaults()
      await db().flush()
    }
  }

  /** Usuário (token antigo, de antes do app guardar o usuário) e banco local abertos. */
  async function ensureSession() {
    if (!token.value) return
    if (!user.value) {
      try {
        user.value = await authApi.me()
        writeUser(user.value)
      } catch {
        return
      }
    }
    await openSession(user.value.id)
  }

  /**
   * Sair. Os dados deste usuário são apagados do aparelho, a não ser que ainda haja gravações que não chegaram
   * ao servidor: nesse caso ficam guardados (só para este usuário) e sobem no próximo login.
   */
  async function logout() {
    const id = user.value?.id
    if (id != null) {
      try {
        await syncNow()
      } catch {
        // sem rede: decide abaixo pelo que ficou pendente
      }
      const keep = syncState.pending > 0
      if (keep) closeSession()
      else await wipeSession(id)
    } else {
      closeSession()
    }
    expire()
  }

  /** Sessão expirada (401): pede login de novo, sem apagar os dados do aparelho. */
  function expire() {
    token.value = null
    user.value = null
    tokenStorage.set(null)
    writeUser(null)
    closeSession()
    clearQueries()
  }

  return { token, user, isAuthenticated, login, register, ensureSession, logout, expire }
})
