import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminLayout from '../src/components/AdminLayout.vue'
import { getSession } from '../src/api/auth'
import { i18n, setLocale } from '../src/i18n'

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

describe('AdminLayout', () => {
  beforeEach(() => setLocale('zh'))

  it('renders the sidebar navigation, locale switch and slot', async () => {
    const wrapper = mount(AdminLayout, {
      slots: { default: '<table data-testid="content"></table>' },
      global: { plugins: [i18n], stubs: { RouterLink: routerLinkStub } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Auth Server')
    expect(wrapper.text()).toContain('客户端管理')
    expect(wrapper.find('button[data-locale="en"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="content"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('alice')
  })

  it('keeps rendering the shell when the session request fails', async () => {
    vi.mocked(getSession).mockRejectedValueOnce(new Error('offline'))
    const wrapper = mount(AdminLayout, {
      slots: { default: '<p>body</p>' },
      global: { plugins: [i18n], stubs: { RouterLink: routerLinkStub } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('客户端管理')
    expect(wrapper.text()).toContain('body')
  })
})
