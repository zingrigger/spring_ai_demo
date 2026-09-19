<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import LocaleSwitch from './LocaleSwitch.vue'

defineProps<{ title: string; subtitle?: string }>()

const { t } = useI18n()
</script>

<template>
  <div class="min-h-screen md:flex">
    <aside class="hidden bg-gradient-to-br from-indigo-600 via-violet-600 to-purple-500 p-10 text-white md:flex md:w-2/5 md:flex-col md:justify-between">
      <div class="flex items-center gap-2 text-sm font-semibold">
        <span class="h-6 w-6 rounded-md bg-white/90"></span>
        <span>{{ t('brand.name') }}</span>
      </div>
      <div>
        <p class="whitespace-pre-line text-2xl font-semibold leading-snug">{{ t('brand.slogan') }}</p>
        <p class="mt-3 text-sm text-white/75">OAuth 2.1 / OIDC</p>
      </div>
    </aside>
    <main class="flex min-h-screen flex-1 items-center justify-center bg-slate-50 p-6">
      <div class="w-full max-w-sm rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <div class="mb-4 flex justify-end">
          <LocaleSwitch />
        </div>
        <h1 class="text-lg font-semibold text-slate-900">{{ title }}</h1>
        <p v-if="subtitle" class="mt-1 text-sm text-slate-500">{{ subtitle }}</p>
        <slot />
      </div>
    </main>
  </div>
</template>
