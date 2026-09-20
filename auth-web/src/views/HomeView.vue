<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AuthLayout from '../components/AuthLayout.vue'
import { getSession, logout, type SessionState } from '../api/auth'
import { navigate } from '../navigation'

const { t } = useI18n()
const session = ref<SessionState | null>(null)

onMounted(async () => {
  session.value = await getSession()
  if (!session.value.authenticated) {
    navigate('/login')
  }
})

async function signOut(): Promise<void> {
  await logout()
  navigate('/login')
}
</script>

<template>
  <AuthLayout :title="t('home.title')" :subtitle="t('home.subtitle')">
    <div v-if="session?.user" class="mt-6 grid gap-4">
      <dl class="grid gap-3 rounded-lg border border-slate-200 p-4">
        <div>
          <dt class="text-xs uppercase tracking-wide text-slate-500">{{ t('home.account') }}</dt>
          <dd class="text-sm font-semibold text-slate-900">{{ session.user.account }}</dd>
        </div>
        <div>
          <dt class="text-xs uppercase tracking-wide text-slate-500">{{ t('home.organization') }}</dt>
          <dd class="text-sm font-semibold text-slate-900">{{ session.organization?.name ?? '—' }}</dd>
        </div>
      </dl>
      <RouterLink v-if="session?.platformAdmin" to="/admin/clients"
                  class="rounded-lg bg-indigo-600 px-4 py-2 text-center text-sm font-semibold text-white transition hover:bg-indigo-500">
        {{ t('clientAdmin.list.entry') }}
      </RouterLink>
      <button type="button"
              class="rounded-lg border border-slate-300 px-4 py-2 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
              @click="signOut">
        {{ t('home.logout') }}
      </button>
    </div>
  </AuthLayout>
</template>
