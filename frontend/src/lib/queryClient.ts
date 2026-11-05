import { QueryClient } from '@tanstack/vue-query'
import { onDataChange } from '@/data/session'
import { invalidateEnsured } from '@/services/recurring'

/**
 * Leituras das telas. Os dados já estão no aparelho (IndexedDB + memória, ver `@/data`), então não há mais cache
 * de API a persistir: as consultas calculam na hora a partir dos dados locais e ficam válidas até os dados mudarem.
 * Qualquer gravação (deste aparelho, de outra aba ou vinda da sincronização) invalida tudo; as telas abertas
 * recalculam em segundo plano, sem piscar.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: Infinity,
      gcTime: 1000 * 60 * 30,
      refetchOnWindowFocus: false,
      refetchOnReconnect: false,
      retry: false,
      // As consultas são locais: rodam com ou sem rede (o padrão do TanStack pausa tudo quando o navegador está offline).
      networkMode: 'always',
    },
    mutations: { networkMode: 'always' },
  },
})

let pending = false
onDataChange((origin) => {
  // Dados que chegaram de fora (outro aparelho, outra aba) podem trazer regras recorrentes novas ou alteradas.
  if (origin === 'remote') invalidateEnsured()
  if (pending) return
  pending = true
  queueMicrotask(() => {
    pending = false
    void queryClient.invalidateQueries()
  })
})

/** Logout: esquece as consultas calculadas (os dados do aparelho são tratados pela sessão). */
export function clearQueries() {
  queryClient.clear()
}
