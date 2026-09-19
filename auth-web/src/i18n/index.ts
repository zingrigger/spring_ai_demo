import { createI18n } from 'vue-i18n'
import en from './en'
import zh from './zh'

export const LOCALE_KEY = 'auth-web.locale'

export type Locale = 'zh' | 'en'

export function detectLocale(): Locale {
  try {
    const saved = localStorage.getItem(LOCALE_KEY)
    if (saved === 'zh' || saved === 'en') return saved
  } catch {
    // 隐私模式下 localStorage 可能不可用，退回浏览器语言
  }
  const language = navigator.language?.toLowerCase() ?? ''
  return language.startsWith('en') ? 'en' : 'zh'
}

export const i18n = createI18n({
  legacy: false,
  locale: detectLocale(),
  fallbackLocale: 'zh',
  messages: { zh, en },
})

export function setLocale(locale: Locale): void {
  i18n.global.locale.value = locale
  try {
    localStorage.setItem(LOCALE_KEY, locale)
  } catch {
    // 忽略存储失败：语言仍然在当前页面生效
  }
}
