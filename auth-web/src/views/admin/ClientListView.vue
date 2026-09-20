<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AuthLayout from '../../components/AuthLayout.vue'
import { ApiError } from '../../api/client'
import { listClients, type ClientSummary } from '../../api/adminClients'

const { t } = useI18n()
const clients = ref<ClientSummary[]>([])
const query = ref('')
const error = ref<string | null>(null)

async function load(): Promise<void> {
  error.value = null
  try {
    clients.value = await listClients(query.value.trim())
  } catch (e) {
    error.value = e instanceof ApiError && e.status === 403 ? 'denied' : 'failed'
  }
}

onMounted(load)
</script>

<template>
  <AuthLayout :title="t('clientAdmin.list.title')" :subtitle="t('clientAdmin.list.subtitle')">
    <div class="mt-6 flex flex-wrap items-center gap-2">
      <form class="flex flex-1 gap-2" @submit.prevent="load">
        <input v-model="query" data-field="query" type="search" :placeholder="t('clientAdmin.list.search')"
               class="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
        <button type="submit"
                class="rounded-lg border border-slate-300 px-4 py-2 text-sm font-semibold text-slate-700 transition hover:bg-slate-50">
          {{ t('clientAdmin.list.searchAction') }}
        </button>
      </form>
      <RouterLink to="/admin/clients/new"
                  class="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500">
        {{ t('clientAdmin.list.create') }}
      </RouterLink>
    </div>

    <p v-if="error" class="mt-4 text-sm text-rose-600">{{ t(`clientAdmin.errors.${error}`) }}</p>

    <table v-else class="mt-6 w-full border-collapse text-left text-sm">
      <thead>
        <tr class="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
          <th class="py-2">{{ t('clientAdmin.list.columns.clientId') }}</th>
          <th class="py-2">{{ t('clientAdmin.list.columns.name') }}</th>
          <th class="py-2">{{ t('clientAdmin.list.columns.type') }}</th>
          <th class="py-2">{{ t('clientAdmin.list.columns.grantTypes') }}</th>
          <th class="py-2">{{ t('clientAdmin.list.columns.scopes') }}</th>
          <th class="py-2">{{ t('clientAdmin.list.columns.status') }}</th>
          <th class="py-2">{{ t('clientAdmin.list.columns.updatedBy') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="client in clients" :key="client.clientId" class="border-b border-slate-100">
          <td class="py-2">
            <RouterLink :to="`/admin/clients/${client.clientId}`" class="font-semibold text-indigo-600">
              {{ client.clientId }}
            </RouterLink>
          </td>
          <td class="py-2">{{ client.clientName }}</td>
          <td class="py-2">{{ t(`clientAdmin.type.${client.type}`) }}</td>
          <td class="py-2">{{ client.grantTypes.join(', ') }}</td>
          <td class="py-2">{{ client.scopes.join(', ') }}</td>
          <td class="py-2">{{ client.enabled ? t('clientAdmin.enabled') : t('clientAdmin.disabled') }}</td>
          <td class="py-2">{{ client.updatedBy ?? '—' }}</td>
        </tr>
      </tbody>
    </table>
  </AuthLayout>
</template>
