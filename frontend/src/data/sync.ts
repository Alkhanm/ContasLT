import type { LocalStore } from './store'
import type { Entity, Id, Stored } from './schema'

/** Registro como o servidor devolve. */
export interface RemoteRecord {
  id: Id
  entity: Entity
  data: unknown
  deleted: boolean
  version: number
  updatedAt: number
}

export interface SyncTransport {
  pull(since: number, limit: number): Promise<{ records: RemoteRecord[]; cursor: number; hasMore: boolean }>
  push(changes: { id: Id; entity: Entity; data: unknown; deleted: boolean; updatedAt: number }[]): Promise<{
    accepted: number
    rejected: RemoteRecord[]
  }>
}

const PUSH_BATCH = 500
const PULL_PAGE = 1000
export const CURSOR_KEY = 'syncCursor'
export const INITIAL_DONE_KEY = 'initialSyncDone'

const toStored = (r: RemoteRecord): Stored => ({
  id: r.id,
  entity: r.entity,
  data: (r.deleted ? null : r.data) as Stored['data'],
  deleted: r.deleted,
  updatedAt: r.updatedAt,
})

/**
 * Uma rodada de sincronização: primeiro sobe o outbox (em lotes), depois baixa tudo acima do cursor. O que o
 * servidor recusou (ele tinha uma gravação mais nova) volta na resposta e é aplicado aqui mesmo.
 * Devolve quantos registros chegaram do servidor e mudaram algo.
 */
export async function syncOnce(store: LocalStore, transport: SyncTransport): Promise<{ pushed: number; pulled: number }> {
  let pushed = 0
  for (let round = 0; round < 1000; round++) {
    const batch = await store.pendingBatch(PUSH_BATCH)
    if (!batch.length) break
    const res = await transport.push(batch.map((r) => ({
      id: r.id, entity: r.entity, data: r.deleted ? null : r.data, deleted: r.deleted, updatedAt: r.updatedAt,
    })))
    // Aceito ou recusado, o que subiu sem mudar de novo sai do outbox; o recusado é substituído pela versão do servidor.
    await store.acknowledge(batch)
    if (res.rejected.length) await store.applyRemote(res.rejected.map(toStored))
    pushed += batch.length
    if (batch.length < PUSH_BATCH) break
  }

  let pulled = 0
  let cursor = (await store.getMeta<number>(CURSOR_KEY)) ?? 0
  for (let page = 0; page < 10_000; page++) {
    const res = await transport.pull(cursor, PULL_PAGE)
    pulled += await store.applyRemote(res.records.map(toStored))
    cursor = res.cursor
    await store.setMeta(CURSOR_KEY, cursor)
    if (!res.hasMore) break
  }
  await store.setMeta(INITIAL_DONE_KEY, true)
  return { pushed, pulled }
}
