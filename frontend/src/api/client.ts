const TOKEN_KEY = 'contaslt.token'
// Sem barra no fim: os caminhos já começam com /api.
const BASE = (import.meta.env.VITE_API_URL ?? '').replace(/\/+$/, '')

export class ApiError extends Error {
  status: number
  errors: Record<string, string>

  constructor(status: number, message: string, errors: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.errors = errors
  }
}

export const tokenStorage = {
  get(): string | null {
    try {
      return localStorage.getItem(TOKEN_KEY)
    } catch {
      return null
    }
  },
  set(token: string | null) {
    try {
      if (token) localStorage.setItem(TOKEN_KEY, token)
      else localStorage.removeItem(TOKEN_KEY)
    } catch {
      // armazenamento indisponível (modo privado); segue sem persistir
    }
  },
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  query?: Record<string, string | number | boolean | null | undefined>
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = tokenStorage.get()
  if (token) headers.Authorization = `Bearer ${token}`

  let body: BodyInit | undefined
  if (options.body instanceof FormData) {
    body = options.body
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }

  let url = BASE + path
  if (options.query) {
    const params = new URLSearchParams()
    for (const [k, v] of Object.entries(options.query)) {
      if (v !== undefined && v !== null && v !== '') params.set(k, String(v))
    }
    const qs = params.toString()
    if (qs) url += (url.includes('?') ? '&' : '?') + qs
  }

  let response: Response
  try {
    response = await fetch(url, { method: options.method ?? 'GET', headers, body })
  } catch {
    throw new ApiError(0, 'Sem conexão com o servidor. Verifique sua internet e tente de novo.')
  }

  if (response.status === 401 && !path.startsWith('/api/auth/login')) {
    tokenStorage.set(null)
    window.dispatchEvent(new CustomEvent('contaslt:unauthorized'))
    throw new ApiError(401, 'Sua sessão expirou. Entre novamente.')
  }

  if (response.status === 204) return undefined as T

  const text = await response.text()
  const data = text ? safeJson(text) : null

  if (!response.ok) {
    const detail = (data && (data.detail as string)) || response.statusText || 'Algo deu errado'
    const errors = (data && (data.errors as Record<string, string>)) || {}
    throw new ApiError(response.status, detail, errors)
  }
  return data as T
}

function safeJson(text: string): Record<string, unknown> | null {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

/** Baixa um recurso binário autenticado (imagem, zip). */
export async function apiBlob(path: string, what = 'o arquivo'): Promise<Blob> {
  const headers: Record<string, string> = {}
  const token = tokenStorage.get()
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(BASE + path, { headers })
  if (!response.ok) {
    const data = safeJson(await response.text().catch(() => ''))
    throw new ApiError(response.status, (data?.detail as string) || `Não foi possível carregar ${what}`)
  }
  return response.blob()
}

/** Baixa um recurso binário autenticado (ex.: imagem de comprovante) e devolve uma URL de objeto. */
export async function apiObjectUrl(path: string): Promise<string> {
  return URL.createObjectURL(await apiBlob(path, 'a imagem'))
}
