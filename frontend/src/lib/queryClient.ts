import { QueryClient } from '@tanstack/vue-query'
import { createAsyncStoragePersister } from '@tanstack/query-async-storage-persister'
import { persistQueryClient } from '@tanstack/query-persist-client-core'
import { del, get, set } from 'idb-keyval'

/**
 * Cache de leituras da API (mesmo esquema do Fimagion): toda consulta fica em memória e no IndexedDB, então a
 * tela abre na hora com o que já se viu, inclusive depois de recarregar ou reabrir a PWA, e o servidor (lento,
 * 0,5 vCPU) é consultado em segundo plano só quando o dado está velho. Mutações invalidam as chaves certas
 * (ver {@code dataChanged} na store de UI) e o logout apaga tudo.
 *
 * Fora do cache ficam as chamadas que são ações, não leituras: login, salvar, excluir, enviar comprovante e
 * consultar a tarefa de leitura (essa precisa esperar o Ollama do computador terminar de verdade).
 */

/** Versão do formato guardado. Mude quando um DTO deixar de ser compatível com o que já está no IndexedDB. */
const CACHE_BUSTER = 'v1'
const CACHE_KEY = 'contaslt-query-cache'
/** Entrada persistida mais velha que isso é descartada ao abrir o app. */
const CACHE_MAX_AGE = 1000 * 60 * 60 * 24 * 7

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Dado com menos de 1 min é servido do cache sem ir ao servidor; mais velho, mostra o cache e refaz atrás.
      staleTime: 60_000,
      // Alto para o persister conseguir gravar as entradas inativas antes de serem descartadas da memória.
      gcTime: 1000 * 60 * 60 * 24,
      // O worker do computador muda lançamentos sem passar pelo app: ao voltar para a PWA, o que estiver
      // velho é atualizado em segundo plano.
      refetchOnWindowFocus: true,
      refetchOnReconnect: true,
      retry: (failureCount, err) => {
        const status = (err as { status?: number })?.status
        if (status === 401 || status === 403 || status === 404 || status === 422) return false
        return failureCount < 1
      },
    },
  },
})

/** IndexedDB via idb-keyval: não tem o limite de 5 MB do localStorage e não bloqueia a thread principal. */
const persister = createAsyncStoragePersister({
  storage: {
    getItem: (key) => get<string | null>(key).then((v) => v ?? null),
    setItem: (key, value) => set(key, value),
    removeItem: (key) => del(key),
  },
  key: CACHE_KEY,
  throttleTime: 1000,
})

void persistQueryClient({ queryClient, persister, maxAge: CACHE_MAX_AGE, buster: CACHE_BUSTER })

/** Apaga memória e IndexedDB. No logout e na sessão expirada, para dados de uma conta não vazarem para outra. */
export async function clearPersistedCache(): Promise<void> {
  queryClient.clear()
  try {
    await del(CACHE_KEY)
  } catch {
    // sem IndexedDB (modo privado): nada a apagar
  }
}

// Outras abas do mesmo app ficam sincronizadas: uma invalidação aqui vira invalidação lá.
const channel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('contaslt-cache') : null
if (channel) {
  let receiving = false
  queryClient.getQueryCache().subscribe((event) => {
    if (receiving || !event || event.type !== 'updated') return
    if ((event as { action?: { type?: string } }).action?.type !== 'invalidate') return
    try {
      channel.postMessage({ k: event.query.queryKey })
    } catch {
      // chave não clonável: sincronização entre abas é só conforto
    }
  })
  channel.addEventListener('message', (e: MessageEvent<{ k?: unknown }>) => {
    if (!Array.isArray(e.data?.k)) return
    receiving = true
    try {
      void queryClient.invalidateQueries({ queryKey: e.data.k })
    } finally {
      setTimeout(() => { receiving = false }, 0)
    }
  })
}
