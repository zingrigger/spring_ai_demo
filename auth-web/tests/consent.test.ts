import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ConsentView from '../src/views/ConsentView.vue'
import { submitConsent } from '../src/consentForm'
import { getConsent } from '../src/api/auth'
import { i18n, setLocale } from '../src/i18n'

vi.mock('../src/api/auth', () => ({ getConsent: vi.fn() }))
vi.mock('vue-router', () => ({
  useRoute: () => ({
    query: { client_id: 'auth-web-public', scope: ['openid', 'profile', 'weather:read'], state: 'test-state' },
  }),
}))

describe('submitConsent', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {})
  })

  it('posts client_id, state and every approved scope', () => {
    submitConsent({ clientId: 'auth-web-public', state: 'test-state', scopes: ['profile', 'weather:read'] })

    const form = document.forms[0]
    expect(form.method).toBe('post')
    expect(form.getAttribute('action')).toBe('/oauth2/authorize')
    const fields = Array.from(form.elements).map((element) => {
      const input = element as HTMLInputElement
      return [input.name, input.value]
    })
    expect(fields).toEqual([
      ['client_id', 'auth-web-public'],
      ['state', 'test-state'],
      ['scope', 'profile'],
      ['scope', 'weather:read'],
    ])
  })
})

describe('ConsentView', () => {
  beforeEach(() => {
    setLocale('zh')
    document.body.innerHTML = ''
    vi.mocked(getConsent).mockReset()
    vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {})
  })

  it('lists the requested scopes and submits the approved ones', async () => {
    vi.mocked(getConsent).mockResolvedValue({
      clientId: 'auth-web-public',
      clientName: 'Auth Web Client',
      scopes: ['profile', 'weather:read'],
      state: 'test-state',
      organization: { id: 10, name: 'Alpha' },
      user: { account: 'alice', name: 'Alice' },
    })
    const wrapper = mount(ConsentView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(getConsent).toHaveBeenCalledWith({
      clientId: 'auth-web-public',
      scopes: ['openid', 'profile', 'weather:read'],
      state: 'test-state',
    })
    expect(wrapper.text()).toContain('Auth Web Client')
    expect(wrapper.text()).toContain('Alpha')
    expect(wrapper.text()).toContain('读取天气数据')

    await wrapper.findAll('input[name="scope"]')[1].setValue(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const fields = Array.from(document.forms[0].elements).map((element) => {
      const input = element as HTMLInputElement
      return [input.name, input.value]
    })
    expect(fields).toEqual([
      ['client_id', 'auth-web-public'],
      ['state', 'test-state'],
      ['scope', 'profile'],
    ])
  })
})
