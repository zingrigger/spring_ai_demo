import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import AuthLayout from '../src/components/AuthLayout.vue'
import { i18n, setLocale } from '../src/i18n'

describe('AuthLayout', () => {
  beforeEach(() => setLocale('zh'))

  it('renders the brand column, the locale switch and the slot', () => {
    const wrapper = mount(AuthLayout, {
      props: { title: '登录', subtitle: '使用你的组织账号继续' },
      slots: { default: '<input name="account" />' },
      global: { plugins: [i18n] },
    })

    expect(wrapper.text()).toContain('Auth Server')
    expect(wrapper.text()).toContain('安全登录')
    expect(wrapper.get('input[name="account"]').exists()).toBe(true)
    expect(wrapper.find('button[data-locale="en"]').exists()).toBe(true)
    expect(wrapper.get('aside').classes()).toContain('hidden')
  })
})
