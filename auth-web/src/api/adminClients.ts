import { api } from './client'

export type ClientType = 'web' | 'machine' | 'public' | 'custom'

export interface ClientSummary {
  clientId: string
  clientName: string
  type: ClientType
  grantTypes: string[]
  scopes: string[]
  enabled: boolean
  updatedAt: string | null
  updatedBy: string | null
}

export interface ClientTokenSettings {
  accessTokenTimeToLive: string
  refreshTokenTimeToLive: string
  authorizationCodeTimeToLive: string
  idTokenSignatureAlgorithm: string
  accessTokenFormat: string
}

export interface ClientDetail {
  clientId: string
  clientName: string
  type: ClientType
  clientAuthenticationMethods: string[]
  grantTypes: string[]
  redirectUris: string[]
  postLogoutRedirectUris: string[]
  scopes: string[]
  requireProofKey: boolean
  requireAuthorizationConsent: boolean
  clientIdIssuedAt: string | null
  clientSecretExpiresAt: string | null
  enabled: boolean
  tokenSettings: ClientTokenSettings
}

export interface CreateClientPayload {
  clientId: string
  clientName: string
  type: 'web' | 'machine' | 'public'
  redirectUris: string[]
  postLogoutRedirectUris: string[]
  scopes: string[]
  requireAuthorizationConsent: boolean
}

export interface UpdateClientPayload {
  clientName: string
  redirectUris: string[]
  postLogoutRedirectUris: string[]
  scopes: string[]
  grantTypes: string[]
  clientAuthenticationMethods: string[]
  requireAuthorizationConsent: boolean
  requireProofKey: boolean
}

export interface CreatedClient {
  client: ClientDetail
  clientSecret: string | null
}

export function listClients(query = ''): Promise<ClientSummary[]> {
  const suffix = query ? `?query=${encodeURIComponent(query)}` : ''
  return api<ClientSummary[]>(`/api/admin/clients${suffix}`)
}

export function getClient(clientId: string): Promise<ClientDetail> {
  return api<ClientDetail>(`/api/admin/clients/${encodeURIComponent(clientId)}`)
}

export function createClient(payload: CreateClientPayload): Promise<CreatedClient> {
  return api<CreatedClient>('/api/admin/clients', { method: 'POST', body: JSON.stringify(payload) })
}

export function updateClient(clientId: string, payload: UpdateClientPayload): Promise<ClientDetail> {
  return api<ClientDetail>(`/api/admin/clients/${encodeURIComponent(clientId)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function rotateClientSecret(clientId: string): Promise<{ clientSecret: string }> {
  return api<{ clientSecret: string }>(`/api/admin/clients/${encodeURIComponent(clientId)}/secret`, {
    method: 'POST',
  })
}

export function setClientEnabled(clientId: string, enabled: boolean): Promise<ClientDetail> {
  const action = enabled ? 'enable' : 'disable'
  return api<ClientDetail>(`/api/admin/clients/${encodeURIComponent(clientId)}/${action}`, { method: 'POST' })
}

export function deleteClient(clientId: string): Promise<void> {
  return api<void>(`/api/admin/clients/${encodeURIComponent(clientId)}`, { method: 'DELETE' })
}
