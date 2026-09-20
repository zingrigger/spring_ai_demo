import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createSessionGuard } from '../src/router'
import type { SessionState } from '../src/api/auth'
import { navigate } from '../src/navigation'

vi.mock('../src/navigation', () => ({ navigate: vi.fn() }))

function session(overrides: Partial<SessionState> = {}): SessionState {
  return {
    authenticated: true,
    user: { account: 'alice', name: 'Alice' },
    organization: { id: 10, name: 'Alpha' },
    pending: null,
    platformAdmin: false,
    ...overrides,
  }
}

function route(path: string) {
  return { path } as never
}

describe('session guard', () => {
  beforeEach(() => vi.mocked(navigate).mockReset())

  it('sends anonymous visitors to the sign-in route', async () => {
    const guard = createSessionGuard(async () => session({ authenticated: false, user: null, organization: null }))

    expect(await guard(route('/organizations'))).toEqual({ path: '/login' })
    expect(await guard(route('/login'))).toBe(true)
  })

  it('sends signed-in users without an organization to the picker', async () => {
    const guard = createSessionGuard(async () => session({ organization: null }))

    expect(await guard(route('/'))).toEqual({ path: '/organizations' })
    expect(await guard(route('/organizations'))).toBe(true)
  })

  it('resumes a pending authorization request from / and /login', async () => {
    const guard = createSessionGuard(async () => session({ pending: '/oauth2/authorize?client_id=auth-web-public' }))

    expect(await guard(route('/'))).toBe(false)
    expect(navigate).toHaveBeenCalledWith('/oauth2/authorize?client_id=auth-web-public')
  })

  it('keeps bound users off the sign-in and picker routes', async () => {
    const guard = createSessionGuard(async () => session())

    expect(await guard(route('/login'))).toEqual({ path: '/' })
    expect(await guard(route('/organizations'))).toEqual({ path: '/' })
    expect(await guard(route('/consent'))).toBe(true)
  })

  it('lets platform admins reach the admin routes without an organization', async () => {
    const guard = createSessionGuard(async () => session({ platformAdmin: true, organization: null }))

    expect(await guard(route('/admin/clients'))).toBe(true)
  })

  it('sends non-admins away from the admin routes', async () => {
    const guard = createSessionGuard(async () => session())

    expect(await guard(route('/admin/clients'))).toEqual({ path: '/' })
  })
})
