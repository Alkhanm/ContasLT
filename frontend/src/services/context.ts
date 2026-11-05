import { ApiError } from '@/api/client'
import { store } from '@/data/session'
import type { LocalStore } from '@/data/store'

/**
 * Acesso dos serviços aos dados do usuário. Em produção é a sessão aberta; nos testes, um store em memória.
 * Os serviços são o antigo backend rodando no aparelho: leem e gravam de forma síncrona na memória, e a
 * camada `api` espera o IndexedDB ({@code flush}) antes de responder.
 */
let testStore: LocalStore | null = null

export function db(): LocalStore {
  return testStore ?? store()
}

export function useStoreForTests(s: LocalStore | null) {
  testStore = s
}

export function isTestStore() {
  return testStore !== null
}

/** Regra de negócio violada (era o 422 do backend): a mensagem vai direto para o usuário. */
export class BusinessError extends ApiError {
  constructor(message: string) {
    super(422, message)
  }
}

export class NotFoundError extends ApiError {
  constructor(message: string) {
    super(404, message)
  }
}

/** Validação de campos (era o 400 com `errors` do Bean Validation). */
export class ValidationError extends ApiError {
  constructor(errors: Record<string, string>) {
    super(400, 'Dados inválidos', errors)
  }
}

/** Junta erros de campo e lança se houver algum. */
export function validate(checks: [field: string, ok: boolean, message: string][]) {
  const errors: Record<string, string> = {}
  for (const [field, ok, message] of checks) if (!ok && !errors[field]) errors[field] = message
  if (Object.keys(errors).length) throw new ValidationError(errors)
}

export function require<T>(value: T | null | undefined, message: string): T {
  if (value == null) throw new NotFoundError(message)
  return value
}
