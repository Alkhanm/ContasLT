import { describe, expect, it } from 'vitest'
import { ContasDb } from './db'
import { LocalStore } from './store'
import { syncOnce, type RemoteRecord, type SyncTransport } from './sync'
import type { AccountRec } from './schema'

/** Servidor falso com a mesma regra do SyncService: vence o updatedAt maior (empate aceita). */
class FakeServer implements SyncTransport {
  rows = new Map<string, RemoteRecord>()
  version = 0
  async pull(since: number, limit: number) {
    const records = [...this.rows.values()].filter((r) => r.version > since).sort((a, b) => a.version - b.version).slice(0, limit)
    return { records, cursor: records.length ? records[records.length - 1].version : since, hasMore: records.length === limit }
  }
  async push(changes: Parameters<SyncTransport['push']>[0]) {
    const rejected: RemoteRecord[] = []
    let accepted = 0
    for (const c of changes) {
      const cur = this.rows.get(c.id)
      if (cur && cur.updatedAt > c.updatedAt) {
        rejected.push(cur)
        continue
      }
      this.rows.set(c.id, { ...c, data: c.deleted ? null : c.data, version: ++this.version })
      accepted++
    }
    return { accepted, rejected }
  }
}

let n = 0
async function device() {
  const store = new LocalStore(new ContasDb(`test-${++n}-${Math.random()}`))
  await store.load()
  return store
}

const account = (id: string, name: string): AccountRec => ({
  id, name, type: 'CHECKING', initialBalance: 0, color: '#000', archived: false, closingDay: null, dueDay: null, createdAt: '2026-01-01T00:00',
})

describe('sincronização', () => {
  it('leva gravações de um aparelho para outro e respeita a gravação mais nova', async () => {
    const server = new FakeServer()
    const a = await device()
    const b = await device()

    a.put('account', account('x', 'Nubank'))
    await syncOnce(a, server)
    await syncOnce(b, server)
    expect(b.get('account', 'x')?.name).toBe('Nubank')
    expect(await a.pendingCount()).toBe(0)

    // B edita depois de A: a edição de B vence nos dois.
    a.put('account', account('x', 'Nubank A'))
    await new Promise((r) => setTimeout(r, 2))
    b.put('account', account('x', 'Nubank B'))
    await syncOnce(b, server)
    await syncOnce(a, server)
    expect(a.get('account', 'x')?.name).toBe('Nubank B')
    expect(await a.pendingCount()).toBe(0)
    await syncOnce(b, server)
    expect(b.get('account', 'x')?.name).toBe('Nubank B')

    // Exclusão chega como marcação.
    b.remove('account', 'x')
    await syncOnce(b, server)
    await syncOnce(a, server)
    expect(a.get('account', 'x')).toBeNull()
    expect(a.known('account', 'x')).toBe(true)
  })

  it('vencimento gerado nunca desfaz a edição do mesmo vencimento em outro aparelho', async () => {
    const server = new FakeServer()
    const a = await device()
    const b = await device()
    a.put('account', account('occ', 'gerado'), { generated: true })
    await syncOnce(a, server)
    a.put('account', account('occ', 'pago'))
    await syncOnce(a, server)
    // B, offline, gerou o mesmo vencimento (mesmo id determinístico).
    b.put('account', account('occ', 'gerado'), { generated: true })
    await syncOnce(b, server)
    expect(b.get('account', 'occ')?.name).toBe('pago')
    expect(server.rows.get('occ')?.data).toMatchObject({ name: 'pago' })
  })

  it('dados persistem no IndexedDB e o outbox sobrevive a reabrir o app', async () => {
    const name = `persist-${Math.random()}`
    const s1 = new LocalStore(new ContasDb(name))
    await s1.load()
    s1.put('account', account('p', 'Caixa'))
    await s1.flush()
    const s2 = new LocalStore(new ContasDb(name))
    await s2.load()
    expect(s2.get('account', 'p')?.name).toBe('Caixa')
    expect(await s2.pendingCount()).toBe(1)
  })
})
