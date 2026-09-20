export interface ApiErrorDetail {
  field: string
  code: string
}

export class ApiError extends Error {
  readonly status: number

  readonly code: string

  readonly details: ApiErrorDetail[]

  constructor(status: number, code: string, details: ApiErrorDetail[] = []) {
    super(code)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
  }
}

export function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|;\\s*)${name}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  if (init.body !== undefined) headers.set('Content-Type', 'application/json')
  if (method !== 'GET' && method !== 'HEAD') {
    const token = readCookie('XSRF-TOKEN')
    if (token) headers.set('X-XSRF-TOKEN', token)
  }

  const response = await fetch(path, { ...init, method, headers, credentials: 'same-origin' })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : undefined
  if (!response.ok) {
    throw new ApiError(response.status, payload?.error ?? `http_${response.status}`, payload?.details ?? [])
  }
  return payload as T
}
