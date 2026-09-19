import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import LocaleSwitch from '../src/components/LocaleSwitch.vue'
import { LOCALE_KEY, i18n, setLocale } from '../src/i18n'

describe('LocaleSwitch', () => {
  beforeEach(() => {
    localStorage.clear()
    setLocale('zh')
  })

  it('switches the active locale and persists the choice', async () => {
    const wrapper = mount(LocaleSwitch, { global: { plugins: [i18n] } })

    await wrapper.get('button[data-locale="en"]').trigger('click')

    expect(i18n.global.locale.value).toBe('en')
    expect(localStorage.getItem(LOCALE_KEY)).toBe('en')
  })
})
