<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AdminLayout from '../../components/AdminLayout.vue'
import { ApiError } from '../../api/client'
import { listClients, type ClientSummary } from '../../api/adminClients'

const { t, locale } = useI18n()
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

/** 单元格里最多展示几个标签，其余折叠成 +N */
function shown(values: string[], limit = 2): string[] {
  return values.slice(0, limit)
}

function hiddenCount(values: string[], limit = 2): number {
  return Math.max(values.length - limit, 0)
}

function formatUpdatedAt(value: string | null): string | null {
  if (!value) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  return new Intl.DateTimeFormat(locale.value === 'zh' ? 'zh-CN' : 'en-US', {
    month: '2-digit',
    day: '2-digit',
  }).format(date)
}

onMounted(load)
</script>

<template>
  <AdminLayout>
    <header class="flex flex-wrap items-start justify-between gap-4">
      <div>
        <h1 class="text-xl font-semibold text-slate-900 md:text-2xl">{{ t('clientAdmin.list.title') }}</h1>
        <p class="mt-1 text-sm text-slate-500">{{ t('clientAdmin.list.subtitle') }}</p>
      </div>
      <RouterLink
        to="/admin/clients/new"
        class="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500"
      >
        {{ t('clientAdmin.list.create') }}
      </RouterLink>
    </header>

    <form
      class="mt-6 flex flex-wrap items-center gap-2 rounded-xl border border-slate-200 bg-white p-3"
      @submit.prevent="load"
    >
      <div class="relative flex min-w-56 flex-1 items-center">
        <svg class="pointer-events-none absolute left-3 h-4 w-4 text-slate-400" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
          <path
            fill-rule="evenodd"
            d="M9 3.5a5.5 5.5 0 1 0 3.4 9.84l3.13 3.13a1 1 0 0 0 1.42-1.42l-3.13-3.13A5.5 5.5 0 0 0 9 3.5ZM5.5 9a3.5 3.5 0 1 1 7 0 3.5 3.5 0 0 1-7 0Z"
            clip-rule="evenodd"
          />
        </svg>
        <input
          v-model="query"
          data-field="query"
          type="search"
          :placeholder="t('clientAdmin.list.search')"
          class="w-full rounded-lg border border-slate-200 py-2 pl-9 pr-3 text-sm outline-none transition focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
        />
      </div>
      <button
        type="submit"
        class="rounded-lg border border-slate-300 px-4 py-2 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
      >
        {{ t('clientAdmin.list.searchAction') }}
      </button>
      <span class="ml-auto text-xs text-slate-500">{{ t('clientAdmin.list.count', { count: clients.length }) }}</span>
    </form>

    <p v-if="error" class="mt-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
      {{ t(`clientAdmin.errors.${error}`) }}
    </p>

    <div v-else class="mt-4 overflow-hidden rounded-xl border border-slate-200 bg-white">
      <div v-if="!clients.length" class="px-6 py-14 text-center">
        <p class="text-sm font-medium text-slate-700">{{ t('clientAdmin.list.empty') }}</p>
        <p class="mt-1 text-xs text-slate-500">{{ t('clientAdmin.list.emptyHint') }}</p>
      </div>

      <div v-else class="overflow-x-auto">
        <table class="w-full min-w-[36rem] border-collapse text-left text-sm lg:min-w-[48rem] xl:min-w-[58rem]">
          <thead>
            <tr class="border-b border-slate-200 bg-slate-50 text-[0.7rem] uppercase tracking-wide text-slate-500">
              <th class="whitespace-nowrap px-4 py-3 font-medium">{{ t('clientAdmin.list.columns.clientId') }}</th>
              <th class="whitespace-nowrap px-4 py-3 font-medium">{{ t('clientAdmin.list.columns.name') }}</th>
              <th class="whitespace-nowrap px-4 py-3 font-medium">{{ t('clientAdmin.list.columns.type') }}</th>
              <th class="hidden whitespace-nowrap px-4 py-3 font-medium xl:table-cell">{{ t('clientAdmin.list.columns.grantTypes') }}</th>
              <th class="hidden whitespace-nowrap px-4 py-3 font-medium lg:table-cell">{{ t('clientAdmin.list.columns.scopes') }}</th>
              <th class="whitespace-nowrap px-4 py-3 font-medium">{{ t('clientAdmin.list.columns.status') }}</th>
              <th class="hidden whitespace-nowrap px-4 py-3 font-medium xl:table-cell">{{ t('clientAdmin.list.columns.updated') }}</th>
              <th class="w-8 px-4 py-3"></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="client in clients"
              :key="client.clientId"
              class="border-b border-slate-100 transition last:border-0 hover:bg-slate-50"
            >
              <td class="px-4 py-3">
                <RouterLink
                  :to="`/admin/clients/${client.clientId}`"
                  class="font-mono text-[0.8rem] font-semibold text-indigo-600 transition hover:text-indigo-500"
                >
                  {{ client.clientId }}
                </RouterLink>
              </td>
              <td class="px-4 py-3 text-slate-900">{{ client.clientName }}</td>
              <td class="px-4 py-3">
                <span class="inline-flex whitespace-nowrap rounded-full border border-slate-200 px-2 py-0.5 text-xs text-slate-600">
                  {{ t(`clientAdmin.type.${client.type}`) }}
                </span>
              </td>
              <td class="hidden px-4 py-3 xl:table-cell">
                <span
                  v-for="grant in shown(client.grantTypes)"
                  :key="grant"
                  class="mr-1 inline-flex rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[0.7rem] text-slate-600"
                >
                  {{ grant }}
                </span>
                <span v-if="hiddenCount(client.grantTypes)" class="text-xs text-slate-400">
                  +{{ hiddenCount(client.grantTypes) }}
                </span>
              </td>
              <td class="hidden px-4 py-3 lg:table-cell">
                <span
                  v-for="scope in shown(client.scopes)"
                  :key="scope"
                  class="mr-1 inline-flex rounded bg-indigo-50 px-1.5 py-0.5 font-mono text-[0.7rem] text-indigo-700"
                >
                  {{ scope }}
                </span>
                <span v-if="hiddenCount(client.scopes)" class="text-xs text-slate-400">
                  +{{ hiddenCount(client.scopes) }}
                </span>
              </td>
              <td class="px-4 py-3">
                <span
                  class="inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-xs"
                  :class="client.enabled ? 'bg-emerald-50 text-emerald-700' : 'bg-slate-100 text-slate-500'"
                >
                  <span class="h-1.5 w-1.5 rounded-full" :class="client.enabled ? 'bg-emerald-500' : 'bg-slate-400'"></span>
                  {{ client.enabled ? t('clientAdmin.enabled') : t('clientAdmin.disabled') }}
                </span>
              </td>
              <td class="hidden px-4 py-3 text-xs text-slate-500 xl:table-cell">
                {{ client.updatedBy ?? '—' }}
                <span v-if="formatUpdatedAt(client.updatedAt)"> · {{ formatUpdatedAt(client.updatedAt) }}</span>
              </td>
              <td class="px-4 py-3 text-slate-300">›</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </AdminLayout>
</template>
