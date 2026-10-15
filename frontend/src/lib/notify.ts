import { toast } from 'vue-sonner'
import { ApiError } from '@/api/client'

export function notifyError(error: unknown, fallback = 'Algo deu errado. Tente de novo.') {
  const message = error instanceof ApiError ? error.message : error instanceof Error ? error.message : fallback
  toast.error(message)
}

export function notifySuccess(message: string) {
  toast.success(message)
}

/** Erros de validação por campo vindos da API (ProblemDetail.errors). */
export function fieldErrors(error: unknown): Record<string, string> {
  return error instanceof ApiError ? error.errors : {}
}
