import type { ContasDb } from './db'
import { ENTITIES, GENERATED_AT, type Entity, type EntityMap, type Id, type Stored } from './schema'

/**
 * Todos os dados do usuário em memória, espelhados no IndexedDB. Leituras são síncronas (as regras de negócio
 * rodam sobre mapas em memória, como antes rodavam sobre o banco); gravações atualizam a memória na hora e vão para
 * o IndexedDB (registro + outbox) numa transação, em segundo plano. {@link flush} espera essa gravação.
 *
 * Conflitos: um registro vindo do servidor só substitui o local se o `updatedAt` dele for maior ou igual (vence a
 * gravação mais nova, em qualquer aparelho). Quem chama não precisa saber disso: só `put`/`remove`.
 */
export class LocalStore {
  private maps = new Map<Entity, Map<Id, Stored>>()
  private clock = 0
  private queue: Stored[] = []
  private persisting: Promise<void> = Promise.resolve()
  private scheduled = false
  private listeners = new Set<(origin: 'local' | 'remote') => void>()
  private notifyScheduled: 'local' | 'remote' | null = null
  /** Avisado quando o IndexedDB recusa uma gravação (ex.: sem espaço). */
  onPersistError: ((err: unknown) => void) | null = null

  readonly db: ContasDb

  constructor(db: ContasDb) {
    this.db = db
    for (const e of ENTITIES) this.maps.set(e, new Map())
  }

  /** Carrega tudo do IndexedDB (abertura do app, ou outra aba gravou). */
  async load() {
    const rows = await this.db.records.toArray()
    for (const m of this.maps.values()) m.clear()
    for (const r of rows) {
      this.mapOf(r.entity).set(r.id, r)
      if (r.updatedAt > this.clock) this.clock = r.updatedAt
    }
  }

  // ---------- Leitura ----------

  list<E extends Entity>(entity: E): EntityMap[E][] {
    const out: EntityMap[E][] = []
    for (const r of this.mapOf(entity).values()) if (!r.deleted && r.data) out.push(r.data as EntityMap[E])
    return out
  }

  get<E extends Entity>(entity: E, id: Id | null | undefined): EntityMap[E] | null {
    if (id == null) return null
    const r = this.mapOf(entity).get(id)
    return r && !r.deleted ? (r.data as EntityMap[E]) : null
  }

  /** Existe registro com este id, mesmo excluído (marcação). */
  known(entity: Entity, id: Id) {
    return this.mapOf(entity).has(id)
  }

  isEmpty() {
    for (const m of this.maps.values()) for (const r of m.values()) if (!r.deleted) return false
    return true
  }

  // ---------- Escrita ----------

  /**
   * Grava (cria ou substitui) um registro. `generated`: vencimento criado pelo gerador de recorrentes, com relógio
   * baixo para nunca vencer uma edição real do mesmo vencimento feita em outro aparelho. Se o id já foi excluído,
   * vai com o relógio atual (é uma recriação de verdade).
   */
  put<E extends Entity>(entity: E, data: EntityMap[E], opts: { generated?: boolean } = {}) {
    const prev = this.mapOf(entity).get(data.id)
    const updatedAt = opts.generated && !prev ? GENERATED_AT : this.tick()
    this.write({ id: data.id, entity, data: structuredClone(data), deleted: false, updatedAt })
  }

  remove(entity: Entity, id: Id) {
    const prev = this.mapOf(entity).get(id)
    if (!prev || prev.deleted) return
    this.write({ id, entity, data: null, deleted: true, updatedAt: this.tick() })
  }

  /** Espera o IndexedDB terminar de gravar o que já está na memória. */
  async flush() {
    if (this.queue.length) this.persistNow()
    await this.persisting
  }

  private write(rec: Stored) {
    this.mapOf(rec.entity).set(rec.id, rec)
    this.queue.push(rec)
    this.schedulePersist()
    this.notify('local')
  }

  private tick() {
    this.clock = Math.max(Date.now(), this.clock + 1)
    return this.clock
  }

  private schedulePersist() {
    if (this.scheduled) return
    this.scheduled = true
    queueMicrotask(() => this.persistNow())
  }

  private persistNow() {
    this.scheduled = false
    const batch = this.queue
    this.queue = []
    if (!batch.length) return
    this.persisting = this.persisting.then(() =>
      this.db.transaction('rw', this.db.records, this.db.outbox, async () => {
        await this.db.records.bulkPut(batch)
        await this.db.outbox.bulkPut(batch)
      }),
    ).catch((err) => {
      // A memória continua certa; o que não foi gravado volta para a fila e é tentado na próxima gravação.
      console.error('[contaslt] falha ao gravar no aparelho', err)
      this.queue.unshift(...batch)
      this.onPersistError?.(err)
    })
  }

  // ---------- Sincronização ----------

  /**
   * Aplica registros vindos do servidor. Vence o mais novo; empate aceita o remoto (é a mesma gravação de volta).
   * O que foi superado sai do outbox. Devolve quantos mudaram algo na memória.
   */
  async applyRemote(records: Stored[]): Promise<number> {
    if (!records.length) return 0
    await this.flush()
    const accepted: Stored[] = []
    for (const r of records) {
      const local = this.mapOf(r.entity).get(r.id)
      if (local && local.updatedAt > r.updatedAt) continue
      accepted.push(r)
    }
    await this.db.transaction('rw', this.db.records, this.db.outbox, async () => {
      await this.db.records.bulkPut(accepted)
      const pending = await this.db.outbox.bulkGet(accepted.map((r) => r.id))
      const superseded = accepted.filter((r, i) => pending[i] && pending[i]!.updatedAt <= r.updatedAt).map((r) => r.id)
      if (superseded.length) await this.db.outbox.bulkDelete(superseded)
    })
    let changed = 0
    for (const r of accepted) {
      const local = this.mapOf(r.entity).get(r.id)
      if (local && local.updatedAt === r.updatedAt && local.deleted === r.deleted) continue
      this.mapOf(r.entity).set(r.id, r)
      if (r.updatedAt > this.clock && r.updatedAt < Date.now() + 86_400_000) this.clock = r.updatedAt
      changed++
    }
    if (changed) this.notify('remote')
    return changed
  }

  /** Próximo lote a enviar. */
  async pendingBatch(limit: number): Promise<Stored[]> {
    await this.flush()
    return this.db.outbox.orderBy('updatedAt').limit(limit).toArray()
  }

  /** O servidor aceitou o lote: sai do outbox o que não mudou de novo enquanto ele subia. */
  async acknowledge(sent: Stored[]) {
    await this.db.transaction('rw', this.db.outbox, async () => {
      const now = await this.db.outbox.bulkGet(sent.map((s) => s.id))
      const done = sent.filter((s, i) => now[i] && now[i]!.updatedAt === s.updatedAt).map((s) => s.id)
      if (done.length) await this.db.outbox.bulkDelete(done)
    })
  }

  async pendingCount() {
    await this.flush()
    return this.db.outbox.count()
  }

  async getMeta<T>(key: string): Promise<T | undefined> {
    return (await this.db.meta.get(key))?.value as T | undefined
  }

  async setMeta(key: string, value: unknown) {
    await this.db.meta.put({ key, value })
  }

  // ---------- Avisos ----------

  /** `origin`: 'local' (gravação neste aparelho) ou 'remote' (chegou da sincronização / outra aba). */
  subscribe(fn: (origin: 'local' | 'remote') => void) {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  /** Outra aba gravou: relê o IndexedDB e avisa. */
  async reloadFromDisk() {
    await this.flush()
    await this.load()
    this.notify('remote')
  }

  private notify(origin: 'local' | 'remote') {
    if (this.notifyScheduled === 'local') return
    const first = this.notifyScheduled === null
    this.notifyScheduled = origin === 'local' ? 'local' : (this.notifyScheduled ?? 'remote')
    if (!first) return
    queueMicrotask(() => {
      const o = this.notifyScheduled ?? 'remote'
      this.notifyScheduled = null
      for (const fn of this.listeners) fn(o)
    })
  }

  private mapOf(entity: Entity) {
    let m = this.maps.get(entity)
    if (!m) {
      m = new Map()
      this.maps.set(entity, m)
    }
    return m
  }
}
