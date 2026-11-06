import { reactive, ref } from 'vue'
import { api, ApiError } from '@/api/client'
import { ContasDb, dbName, deleteUserDb } from './db'
import { LocalStore } from './store'
import { INITIAL_DONE_KEY, syncOnce, type SyncTransport } from './sync'

/**
 * Sessão local do usuário logado: o banco do aparelho, a memória e a sincronização em segundo plano.
 * Sincroniza ao abrir, ao voltar para o app, ao reconectar, logo depois de gravar e a cada poucos minutos.
 * Entre abas: quem grava avisa pelo BroadcastChannel e as outras releem o IndexedDB; só uma aba sincroniza por vez
 * (Web Locks), para não subir o mesmo outbox duas vezes.
 */

const transport: SyncTransport = {
  pull: (since, limit) => api('/api/sync/pull', { query: { since, limit } }),
  push: (changes) => api('/api/sync/push', { method: 'POST', body: { changes } }),
}

export type SyncStatus = 'idle' | 'syncing' | 'offline' | 'error'

export const syncState = reactive({
  status: 'idle' as SyncStatus,
  /** Gravações deste aparelho que ainda não chegaram ao servidor. */
  pending: 0,
  lastSync: null as number | null,
  error: null as string | null,
  /** Já houve uma sincronização completa neste aparelho (antes disso os dados podem estar incompletos). */
  initialDone: false,
})

/** Incrementa a cada mudança nos dados (local ou vinda do servidor/outra aba). */
export const dataVersion = ref(0)

let current: { userId: number; store: LocalStore; dispose: () => void } | null = null
let opening: Promise<LocalStore> | null = null
const changeListeners = new Set<(origin: 'local' | 'remote') => void>()

const afterSyncHooks = new Set<() => Promise<void>>()

/** Roda depois de cada sincronização bem-sucedida (ex.: aplicar leituras de comprovante que terminaram). */
export function onAfterSync(fn: () => Promise<void>) {
  afterSyncHooks.add(fn)
  return () => afterSyncHooks.delete(fn)
}

export function onDataChange(fn: (origin: 'local' | 'remote') => void) {
  changeListeners.add(fn)
  return () => changeListeners.delete(fn)
}

export function hasSession() {
  return current !== null
}

/** A memória de dados do usuário logado. Só chame depois de {@link openSession}. */
export function store(): LocalStore {
  if (!current) throw new Error('Sessão local não aberta')
  return current.store
}

export async function openSession(userId: number): Promise<LocalStore> {
  if (current?.userId === userId) return current.store
  if (opening) return opening
  opening = (async () => {
    closeSession()
    const s = new LocalStore(new ContasDb(dbName(userId)))
    await s.load()
    syncState.initialDone = (await s.getMeta<boolean>(INITIAL_DONE_KEY)) ?? false
    syncState.pending = await s.pendingCount()
    const channel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel(`contaslt-data-${userId}`) : null

    const unsubscribe = s.subscribe((origin) => {
      dataVersion.value++
      for (const fn of changeListeners) fn(origin)
      if (origin === 'local') {
        scheduleSync(1500)
        void s.flush().then(() => channel?.postMessage('changed'))
      } else {
        void s.pendingCount().then((n) => { syncState.pending = n })
      }
    })
    channel?.addEventListener('message', () => { void s.reloadFromDisk() })
    s.onPersistError = () => { syncState.error = 'Não foi possível gravar no aparelho (sem espaço?)' }

    const onVisible = () => { if (document.visibilityState === 'visible') scheduleSync(0) }
    const onOnline = () => scheduleSync(0)
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('online', onOnline)
    const interval = window.setInterval(() => scheduleSync(0), 3 * 60_000)

    current = {
      userId,
      store: s,
      dispose: () => {
        unsubscribe()
        channel?.close()
        document.removeEventListener('visibilitychange', onVisible)
        window.removeEventListener('online', onOnline)
        window.clearInterval(interval)
      },
    }
    dataVersion.value++
    scheduleSync(0)
    return s
  })()
  try {
    return await opening
  } finally {
    opening = null
  }
}

export function closeSession() {
  if (timer) window.clearTimeout(timer)
  timer = null
  current?.dispose()
  current?.store.db.close()
  current = null
  syncState.status = 'idle'
  syncState.pending = 0
  syncState.error = null
  syncState.lastSync = null
  syncState.initialDone = false
}

/** Logout de verdade: apaga os dados deste usuário do aparelho. */
export async function wipeSession(userId: number) {
  if (current?.userId === userId) closeSession()
  await deleteUserDb(userId)
}

let timer: number | null = null
let running: Promise<void> | null = null
let again = false

export function scheduleSync(delay: number) {
  if (!current) return
  if (timer) window.clearTimeout(timer)
  timer = window.setTimeout(() => {
    timer = null
    void syncNow()
  }, delay)
}

/** Sincroniza agora (ou logo depois da rodada em andamento). */
export async function syncNow(): Promise<void> {
  if (!current) return
  if (running) {
    again = true
    return running
  }
  const s = current.store
  running = (async () => {
    do {
      again = false
      await runLocked(() => runOnce(s))
    } while (again && current?.store === s)
  })()
  try {
    await running
  } finally {
    running = null
  }
}

async function runOnce(s: LocalStore) {
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    syncState.status = 'offline'
    return
  }
  syncState.status = 'syncing'
  try {
    await syncOnce(s, transport)
    if (current?.store !== s) return
    syncState.status = 'idle'
    syncState.error = null
    syncState.lastSync = Date.now()
    syncState.initialDone = true
    for (const fn of afterSyncHooks) {
      try {
        await fn()
      } catch (err) {
        console.warn('[contaslt] pós-sincronização falhou', err)
      }
    }
  } catch (e) {
    if (current?.store !== s) return
    const status = e instanceof ApiError ? e.status : -1
    syncState.status = status === 0 ? 'offline' : 'error'
    syncState.error = e instanceof Error ? e.message : String(e)
    // Tenta de novo sozinho daqui a pouco (servidor acordando, rede oscilando).
    if (status !== 401) scheduleSync(30_000)
  } finally {
    if (current?.store === s) syncState.pending = await s.pendingCount()
  }
}

async function runLocked(fn: () => Promise<void>) {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined
  if (!locks || !current) return fn()
  await locks.request(`contaslt-sync-${current.userId}`, fn)
}

/** Espera a primeira sincronização completa deste aparelho (login novo). */
export async function waitInitialSync(): Promise<void> {
  if (syncState.initialDone) return
  await syncNow()
  if (!syncState.initialDone && syncState.error) throw new Error(syncState.error)
}
