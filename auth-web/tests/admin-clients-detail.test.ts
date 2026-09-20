import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ClientDetailView from '../src/views/admin/ClientDetailView.vue'
import { i18n, setLocale } from '../src/i18n'
import {
  deleteClient,
  getClient,
  rotateClientSecret,
  setClientEnabled,
  updateClient,
  type ClientDetail,
} from '../src/api/adminClients'

const routerMock = vi.hoisted(() => ({ push: vi.fn(), clientId: 'auth-machine' }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { clientId: routerMock.clientId } }),
  useRouter: () => ({ push: routerMock.push }),
}))
vi.mock('../src/api/adminClients', () => ({
  getClient: vi.fn(),
  updateClient: vi.fn(),
  rotateClientSecret: vi.fn(),
  setClientEnabled: vi.fn(),
  deleteClient: vi.fn(),
}))

const detail: ClientDetail = {
  clientId: 'auth-machine',
  clientName: 'Auth Machine',
  type: 'machine',
  clientAuthenticationMethods: ['client_secret_basic'],
  grantTypes: ['client_credentials'],
  redirectUris: [],
  postLogoutRedirectUris: [],
  scopes: ['weather:read'],
  requireProofKey: false,
  requireAuthorizationConsent: false,
  clientIdIssuedAt: '2026-09-18T02:00:00Z',
  clientSecretExpiresAt: null,
  enabled: true,
  tokenSettings: {
    accessTokenTimeToLive: 'PT10M',
    refreshTokenTimeToLive: 'PT1H',
    authorizationCodeTimeToLive: 'PT5M',
    idTokenSignatureAlgorithm: 'RS256',
    accessTokenFormat: 'self-contained',
  },
}

describe('ClientDetailView', () => {
  beforeEach(() => {
    setLocale('zh')
    vi.mocked(getClient).mockReset()
    vi.mocked(getClient).mockResolvedValue(detail)
    vi.mocked(updateClient).mockReset()
    vi.mocked(updateClient).mockResolvedValue(detail)
    vi.mocked(rotateClientSecret).mockReset()
    vi.mocked(setClientEnabled).mockReset()
    vi.mocked(deleteClient).mockReset()
    routerMock.push.mockReset()
  })

  it('loads the client and saves edited fields', async () => {
    const wrapper = mount(ClientDetailView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Auth Machine')
    await wrapper.get('[data-field="clientName"]').setValue('Renamed Machine')
    await wrapper.get('[data-field="requireProofKey"]').setValue(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(updateClient).toHaveBeenCalledWith('auth-machine',
      expect.objectContaining({
        clientName: 'Renamed Machine',
        scopes: ['weather:read'],
        requireProofKey: true,
      }))
  })

  it('rotates the secret and shows the new value', async () => {
    vi.mocked(rotateClientSecret).mockResolvedValue({ clientSecret: 'rotated-secret' })
    const wrapper = mount(ClientDetailView, { global: { plugins: [i18n] } })
    await flushPromises()

    await wrapper.get('[data-action="rotate"]').trigger('click')
    await flushPromises()

    expect(rotateClientSecret).toHaveBeenCalledWith('auth-machine')
    expect(wrapper.text()).toContain('rotated-secret')
  })

  it('requires the typed client id before deleting', async () => {
    vi.mocked(deleteClient).mockResolvedValue()
    const wrapper = mount(ClientDetailView, { global: { plugins: [i18n] } })
    await flushPromises()

    await wrapper.get('[data-action="delete"]').trigger('click')
    expect(deleteClient).not.toHaveBeenCalled()

    await wrapper.get('[data-field="deleteConfirm"]').setValue('auth-machine')
    await wrapper.get('[data-action="delete"]').trigger('click')
    await flushPromises()

    expect(deleteClient).toHaveBeenCalledWith('auth-machine')
    expect(routerMock.push).toHaveBeenCalledWith('/admin/clients')
  })
})
