import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoginView from '../src/views/LoginView.vue'
import { ApiError } from '../src/api/client'
import { i18n, setLocale } from '../src/i18n'
import { login } from '../src/api/auth'
import { navigate } from '../src/navigation'

vi.mock('../src/api/auth', () => ({ login: vi.fn() }))
vi.mock('../src/navigation', () => ({ navigate: vi.fn() }))

async function fillAndSubmit(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('input[name="account"]').setValue('alice')
  await wrapper.get('input[name="password"]').setValue('alice-password')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

describe('LoginView', () => {
  beforeEach(() => {
    setLocale('zh')
    vi.mocked(login).mockReset()
    vi.mocked(navigate).mockReset()
  })

  it('submits credentials and follows the next step', async () => {
    vi.mocked(login).mockResolvedValue({ next: '/organizations' })
    const wrapper = mount(LoginView, { global: { plugins: [i18n] } })

    await fillAndSubmit(wrapper)

    expect(login).toHaveBeenCalledWith('alice', 'alice-password')
    expect(navigate).toHaveBeenCalledWith('/organizations')
  })

  it('shows an inline error for rejected credentials', async () => {
    vi.mocked(login).mockRejectedValue(new ApiError(401, 'invalid_credentials'))
    const wrapper = mount(LoginView, { global: { plugins: [i18n] } })

    await fillAndSubmit(wrapper)

    expect(wrapper.get('[role="alert"]').text()).toBe('账号或密码不正确。')
    expect(navigate).not.toHaveBeenCalled()
  })
})
