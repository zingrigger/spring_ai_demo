import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ClientListView from '../src/views/admin/ClientListView.vue'
import { i18n, setLocale } from '../src/i18n'
import { listClients } from '../src/api/adminClients'

vi.mock('../src/api/adminClients', () => ({ listClients: vi.fn() }))

const routerLinkStub = { template: '<a><slot /></a>' }

describe('ClientListView', () => {
  beforeEach(() => {
    setLocale('zh')
    vi.mocked(listClients).mockReset()
  })

  it('renders clients with type, scopes and audit metadata', async () => {
    vi.mocked(listClients).mockResolvedValue([
      {
        clientId: 'auth-machine',
        clientName: 'Auth Machine',
        type: 'machine',
        grantTypes: ['client_credentials'],
        scopes: ['weather:read'],
        enabled: true,
        updatedAt: '2026-09-20T02:00:00Z',
        updatedBy: 'alice',
      },
    ])
    const wrapper = mount(ClientListView, {
      global: { plugins: [i18n], stubs: { RouterLink: routerLinkStub } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('auth-machine')
    expect(wrapper.text()).toContain('weather:read')
    expect(wrapper.text()).toContain('alice')
  })

  it('searches with the typed query', async () => {
    vi.mocked(listClients).mockResolvedValue([])
    const wrapper = mount(ClientListView, {
      global: { plugins: [i18n], stubs: { RouterLink: routerLinkStub } },
    })
    await flushPromises()

    await wrapper.get('[data-field="query"]').setValue('machine')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(listClients).toHaveBeenLastCalledWith('machine')
  })
})
