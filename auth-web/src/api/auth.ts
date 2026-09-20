import { api } from './client'

export interface UserView {
  account: string
  name: string
}

export interface OrganizationView {
  id: number
  name: string
}

export interface SessionState {
  authenticated: boolean
  user: UserView | null
  organization: OrganizationView | null
  pending: string | null
  platformAdmin: boolean
}

export interface ConsentState {
  clientId: string
  clientName: string
  scopes: string[]
  state: string | null
  organization: OrganizationView | null
  user: UserView
}

export function getSession(): Promise<SessionState> {
  return api<SessionState>('/api/auth/session')
}

export function login(account: string, password: string): Promise<{ next: string }> {
  return api<{ next: string }>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ account, password }),
  })
}

export function logout(): Promise<void> {
  return api<void>('/api/auth/logout', { method: 'POST' })
}

export function listOrganizations(): Promise<{ organizations: OrganizationView[]; next: string | null }> {
  return api<{ organizations: OrganizationView[]; next: string | null }>('/api/organizations')
}

export function selectOrganization(orgId: number): Promise<{ next: string }> {
  return api<{ next: string }>('/api/organizations', {
    method: 'POST',
    body: JSON.stringify({ orgId }),
  })
}

export function getConsent(params: { clientId: string; scopes: string[]; state: string | null }): Promise<ConsentState> {
  const query = new URLSearchParams()
  query.set('client_id', params.clientId)
  params.scopes.forEach((scope) => query.append('scope', scope))
  if (params.state) query.set('state', params.state)
  return api<ConsentState>(`/api/consent?${query.toString()}`)
}
