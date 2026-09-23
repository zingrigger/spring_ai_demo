import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ClientListView from '../src/views/admin/ClientListView.vue'
import { i18n, setLocale } from '../src/i18n'
import { listClients } from '../src/api/adminClients'

vi.mock('../src/api/adminClients', () => ({ listClients: vi.fn() }))
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

function mountList() {
  return mount(ClientListView, {
    global: { plugins: [i18n], stubs: { RouterLink: routerLinkStub } },
  })
}

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
    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.text()).toContain('auth-machine')
    expect(wrapper.text()).toContain('weather:read')
    expect(wrapper.text()).toContain('alice')
  })

  it('renders the table columns, type badges and status pills', async () => {
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
      {
        clientId: 'weather-mcp-inspector',
        clientName: 'Weather MCP Inspector',
        type: 'public',
        grantTypes: ['authorization_code'],
        scopes: ['weather:read'],
        enabled: false,
        updatedAt: null,
        updatedBy: null,
      },
    ])
    const wrapper = mountList()
    await flushPromises()

    const headers = wrapper.findAll('th').map((th) => th.text())
    expect(headers).toContain('Client ID')
    expect(headers).toContain('名称')
    expect(headers).toContain('状态')
    expect(wrapper.text()).toContain('机器客户端')
    expect(wrapper.text()).toContain('公共客户端')
    expect(wrapper.text()).toContain('已启用')
    expect(wrapper.text()).toContain('已停用')
    expect(wrapper.text()).toContain('共 2 个客户端')
  })

  it('shows an empty state when no client matches', async () => {
    vi.mocked(listClients).mockResolvedValue([])
    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.text()).toContain('没有找到匹配的客户端')
  })

  it('searches with the typed query', async () => {
    vi.mocked(listClients).mockResolvedValue([])
    const wrapper = mountList()
    await flushPromises()

    await wrapper.get('[data-field="query"]').setValue('machine')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(listClients).toHaveBeenLastCalledWith('machine')
  })
})
