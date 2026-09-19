import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OrganizationsView from '../src/views/OrganizationsView.vue'
import { ApiError } from '../src/api/client'
import { i18n, setLocale } from '../src/i18n'
import { listOrganizations, selectOrganization } from '../src/api/auth'
import { navigate } from '../src/navigation'

vi.mock('../src/api/auth', () => ({ listOrganizations: vi.fn(), selectOrganization: vi.fn() }))
vi.mock('../src/navigation', () => ({ navigate: vi.fn() }))

describe('OrganizationsView', () => {
  beforeEach(() => {
    setLocale('zh')
    vi.mocked(listOrganizations).mockReset()
    vi.mocked(selectOrganization).mockReset()
    vi.mocked(navigate).mockReset()
  })

  it('navigates straight away when the server already bound a single organization', async () => {
    vi.mocked(listOrganizations).mockResolvedValue({
      organizations: [{ id: 30, name: 'Beta' }],
      next: '/oauth2/authorize?client_id=auth-web-public',
    })

    mount(OrganizationsView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(navigate).toHaveBeenCalledWith('/oauth2/authorize?client_id=auth-web-public')
  })

  it('lists organizations and submits the selected one', async () => {
    vi.mocked(listOrganizations).mockResolvedValue({
      organizations: [{ id: 10, name: 'Alpha' }, { id: 20, name: 'Beta' }],
      next: null,
    })
    vi.mocked(selectOrganization).mockResolvedValue({ next: '/' })
    const wrapper = mount(OrganizationsView, { global: { plugins: [i18n] } })
    await flushPromises()

    const options = wrapper.findAll('input[name="orgId"]')
    expect(options).toHaveLength(2)
    await options[1].setValue()
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(selectOrganization).toHaveBeenCalledWith(20)
    expect(navigate).toHaveBeenCalledWith('/')
  })

  it('explains when the account has no organization', async () => {
    vi.mocked(listOrganizations).mockRejectedValue(new ApiError(403, 'access_denied'))
    const wrapper = mount(OrganizationsView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe('当前账号没有可用的组织，请联系管理员。')
  })
})
