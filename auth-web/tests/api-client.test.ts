import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../src/api/client'

describe('api client', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123'
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })

  it('sends the CSRF cookie as a header on writes', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ next: '/organizations' }), { status: 200 }))

    const result = await api<{ next: string }>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ account: 'alice', password: 'alice-password' }),
    })

    const [, init] = vi.mocked(fetch).mock.calls[0]
    const headers = new Headers(init?.headers)
    expect(headers.get('X-XSRF-TOKEN')).toBe('token-123')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(init?.credentials).toBe('same-origin')
    expect(result.next).toBe('/organizations')
  })

  it('maps error bodies to ApiError', async () => {
    vi.mocked(fetch).mockImplementation(async () =>
      new Response(JSON.stringify({ error: 'invalid_credentials' }), { status: 401 }))

    await expect(api('/api/auth/login', { method: 'POST', body: '{}' }))
      .rejects.toMatchObject({ status: 401, code: 'invalid_credentials' })
    await expect(api('/api/auth/login', { method: 'POST', body: '{}' })).rejects.toBeInstanceOf(ApiError)
  })

  it('keeps field level error details', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({
      error: 'invalid_client_metadata',
      details: [{ field: 'redirectUris', code: 'invalid_uri' }],
    }), { status: 400 }))

    await expect(api('/api/admin/clients', { method: 'POST', body: '{}' })).rejects.toMatchObject({
      status: 400,
      code: 'invalid_client_metadata',
      details: [{ field: 'redirectUris', code: 'invalid_uri' }],
    })
  })

  it('resolves undefined for empty 204 responses', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }))

    await expect(api('/api/auth/logout', { method: 'POST' })).resolves.toBeUndefined()
  })
})
