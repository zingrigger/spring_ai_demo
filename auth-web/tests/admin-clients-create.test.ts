import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ClientCreateView from '../src/views/admin/ClientCreateView.vue'
import { i18n, setLocale } from '../src/i18n'
import { createClient, type CreatedClient } from '../src/api/adminClients'

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock }))
vi.mock('../src/api/adminClients', () => ({ createClient: vi.fn() }))
vi.mock('../src/api/auth', () => ({
  getSession: vi.fn().mockResolvedValue({
    authenticated: true,
    user: { account: 'alice', name: 'Alice' },
    organization: null,
    pending: null,
    platformAdmin: true,
  }),
}))

const routerLinkStub = { template: '<a><slot /></a>' }

function mountCreate() {
  return mount(ClientCreateView, {
    global: { plugins: [i18n], stubs: { RouterLink: routerLinkStub } },
  })
}

const created: CreatedClient = {
  client: {
    clientId: 'api-machine',
    clientName: 'API Machine',
    type: 'machine',
    clientAuthenticationMethods: ['client_secret_basic'],
    grantTypes: ['client_credentials'],
    redirectUris: [],
    postLogoutRedirectUris: [],
    scopes: ['weather:read'],
    requireProofKey: false,
    requireAuthorizationConsent: false,
    clientIdIssuedAt: '2026-09-20T02:00:00Z',
    clientSecretExpiresAt: null,
    enabled: true,
    tokenSettings: {
      accessTokenTimeToLive: 'PT10M',
      refreshTokenTimeToLive: 'PT720H',
      authorizationCodeTimeToLive: 'PT5M',
      idTokenSignatureAlgorithm: 'RS256',
      accessTokenFormat: 'self-contained',
    },
  },
  clientSecret: 'top-secret',
}

describe('ClientCreateView', () => {
  beforeEach(() => {
    setLocale('zh')
    vi.mocked(createClient).mockReset()
    routerMock.push.mockReset()
  })

  it('creates a machine client and navigates only after the secret is confirmed', async () => {
    vi.mocked(createClient).mockResolvedValue(created)
    const wrapper = mountCreate()

    await wrapper.get('[data-field="clientId"]').setValue('api-machine')
    await wrapper.get('[data-field="clientName"]').setValue('API Machine')
    await wrapper.get('[data-field="type-machine"]').setValue(true)
    await wrapper.get('[data-field="scopes"]').setValue('weather:read')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(createClient).toHaveBeenCalledWith({
      clientId: 'api-machine',
      clientName: 'API Machine',
      type: 'machine',
      redirectUris: [],
      postLogoutRedirectUris: [],
      scopes: ['weather:read'],
      requireAuthorizationConsent: false,
    })
    expect(wrapper.text()).toContain('top-secret')
    expect(routerMock.push).not.toHaveBeenCalled()

    await wrapper.get('[data-action="finish"]').trigger('click')
    expect(routerMock.push).toHaveBeenCalledWith('/admin/clients/api-machine')
  })

  it('offers the type presets and hides redirect fields for machine clients', async () => {
    const wrapper = mountCreate()

    expect(wrapper.text()).toContain('机器客户端')
    expect(wrapper.text()).toContain('client_credentials')
    expect(wrapper.find('[data-field="redirectUris"]').exists()).toBe(true)

    await wrapper.get('[data-field="type-machine"]').setValue(true)
    expect(wrapper.find('[data-field="redirectUris"]').exists()).toBe(false)
  })
})
