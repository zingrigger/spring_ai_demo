<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getSession, type SessionState } from '../api/auth'
import LocaleSwitch from './LocaleSwitch.vue'

const { t } = useI18n()
const session = ref<SessionState | null>(null)

const initial = computed(() => session.value?.user?.account.slice(0, 1).toUpperCase() ?? '?')

onMounted(async () => {
  try {
    session.value = await getSession()
  } catch {
    session.value = null
  }
})
</script>

<template>
  <div class="min-h-screen bg-slate-100 md:flex">
    <aside
      class="flex flex-col gap-3 border-b border-slate-200 bg-white px-4 py-3 md:min-h-screen md:w-60 md:shrink-0 md:gap-6 md:border-b-0 md:border-r md:px-5 md:py-6"
    >
      <RouterLink to="/" class="flex items-center gap-2 text-sm font-semibold text-slate-900">
        <span class="h-6 w-6 rounded-md bg-gradient-to-br from-indigo-500 to-purple-500"></span>
        <span>{{ t('brand.name') }}</span>
      </RouterLink>

      <nav class="flex gap-1 md:flex-col">
        <RouterLink
          to="/admin/clients"
          class="rounded-lg px-3 py-2 text-sm font-medium text-slate-600 transition hover:bg-slate-100 hover:text-slate-900"
          active-class="bg-indigo-50 text-indigo-700 hover:bg-indigo-50 hover:text-indigo-700"
        >
          {{ t('admin.nav.clients') }}
        </RouterLink>
      </nav>

      <div class="flex flex-wrap items-center justify-between gap-3 md:mt-auto md:flex-col md:items-start">
        <LocaleSwitch />
        <RouterLink to="/" class="text-xs text-slate-500 transition hover:text-slate-900">
          {{ t('admin.backHome') }}
        </RouterLink>
      </div>

      <div v-if="session?.user" class="flex items-center gap-2 border-t border-slate-100 pt-3">
        <span
          class="flex h-7 w-7 items-center justify-center rounded-full bg-slate-100 text-xs font-semibold text-slate-500"
        >
          {{ initial }}
        </span>
        <span class="min-w-0 flex-1 truncate text-xs text-slate-600" :title="t('admin.account')">
          {{ session.user.account }}
        </span>
      </div>
    </aside>

    <main class="min-w-0 flex-1 px-4 py-6 md:px-8 md:py-10">
      <div class="mx-auto w-full max-w-6xl">
        <slot />
      </div>
    </main>
  </div>
</template>
