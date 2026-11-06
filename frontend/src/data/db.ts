import Dexie, { type Table } from 'dexie'
import type { Entity, Id, Stored } from './schema'

export interface MetaRow {
  key: string
  value: unknown
}

/**
 * IndexedDB de um usuário (um banco por usuário: trocar de conta não mistura dados e uma sessão expirada não apaga
 * o que ainda não subiu). `records` guarda tudo, inclusive exclusões (marcação), `outbox` o que falta enviar ao
 * servidor (a última versão de cada registro alterado) e `meta` o cursor da sincronização.
 */
export class ContasDb extends Dexie {
  records!: Table<Stored, Id>
  outbox!: Table<Stored, Id>
  meta!: Table<MetaRow, string>

  constructor(name: string) {
    super(name)
    this.version(1).stores({
      records: 'id, entity',
      outbox: 'id, updatedAt',
      meta: 'key',
    })
  }
}

export function dbName(userId: number | string) {
  return `contaslt-u${userId}`
}

export async function deleteUserDb(userId: number | string) {
  await Dexie.delete(dbName(userId))
}

export type { Entity }
