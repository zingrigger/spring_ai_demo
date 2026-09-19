import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import HomeView from '../src/views/HomeView.vue'
import { i18n, setLocale } from '../src/i18n'
import { getSession, logout } from '../src/api/auth'
import { navigate } from '../src/navigation'

vi.mock('../src/api/auth', () => ({ getSession: vi.fn(), logout: vi.fn() }))
vi.mock('../src/navigation', () => ({ navigate: vi.fn() }))

describe('HomeView', () => {
  beforeEach(() => {
    setLocale('zh')
    vi.mocked(getSession).mockReset()
    vi.mocked(logout).mockReset()
    vi.mocked(navigate).mockReset()
  })

  it('shows the signed-in account and organization', async () => {
    vi.mocked(getSession).mockResolvedValue({
      authenticated: true,
      user: { account: 'alice', name: 'Alice' },
      organization: { id: 10, name: 'Alpha' },
      pending: null,
    })
    const wrapper = mount(HomeView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('Alpha')
  })

  it('signs out and returns to the login route', async () => {
    vi.mocked(getSession).mockResolvedValue({
      authenticated: true,
      user: { account: 'alice', name: 'Alice' },
      organization: { id: 10, name: 'Alpha' },
      pending: null,
    })
    vi.mocked(logout).mockResolvedValue()
    const wrapper = mount(HomeView, { global: { plugins: [i18n] } })
    await flushPromises()

    await wrapper.get('button:not([data-locale])').trigger('click')
    await flushPromises()

    expect(logout).toHaveBeenCalled()
    expect(navigate).toHaveBeenCalledWith('/login')
  })
})
