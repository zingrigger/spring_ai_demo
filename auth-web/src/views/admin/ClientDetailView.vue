<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import AdminLayout from '../../components/AdminLayout.vue'
import { ApiError } from '../../api/client'
import {
  deleteClient,
  getClient,
  rotateClientSecret,
  setClientEnabled,
  updateClient,
  type ClientDetail,
  type UpdateClientPayload,
} from '../../api/adminClients'

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()
const clientId = String(route.params.clientId)

const client = ref<ClientDetail | null>(null)
const error = ref<string | null>(null)
const newSecret = ref<string | null>(null)
const deleteConfirm = ref('')
const saving = ref(false)

const form = reactive({
  clientName: '',
  redirectUris: '',
  postLogoutRedirectUris: '',
  scopes: '',
  grantTypes: [] as string[],
  clientAuthenticationMethods: [] as string[],
  requireAuthorizationConsent: true,
  requireProofKey: true,
})

const publicClient = computed(() => form.clientAuthenticationMethods.includes('none'))

function splitLines(value: string): string[] {
  return value.split('\n').map((line) => line.trim()).filter((line) => line.length > 0)
}

function splitScopes(value: string): string[] {
  return value.split(/[\s,]+/).map((scope) => scope.trim()).filter((scope) => scope.length > 0)
}

function formatDate(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat(locale.value === 'zh' ? 'zh-CN' : 'en-US', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date)
}

async function load(): Promise<void> {
  error.value = null
  try {
    const detail = await getClient(clientId)
    client.value = detail
    form.clientName = detail.clientName
    form.redirectUris = detail.redirectUris.join('\n')
    form.postLogoutRedirectUris = detail.postLogoutRedirectUris.join('\n')
    form.scopes = detail.scopes.join(' ')
    form.grantTypes = [...detail.grantTypes]
    form.clientAuthenticationMethods = [...detail.clientAuthenticationMethods]
    form.requireAuthorizationConsent = detail.requireAuthorizationConsent
    form.requireProofKey = detail.requireProofKey
  } catch (e) {
    error.value = e instanceof ApiError && e.status === 404 ? 'notFound' : 'failed'
  }
}

async function save(): Promise<void> {
  saving.value = true
  error.value = null
  try {
    const payload: UpdateClientPayload = {
      clientName: form.clientName.trim(),
      redirectUris: splitLines(form.redirectUris),
      postLogoutRedirectUris: splitLines(form.postLogoutRedirectUris),
      scopes: splitScopes(form.scopes),
      grantTypes: form.grantTypes,
      clientAuthenticationMethods: form.clientAuthenticationMethods,
      requireAuthorizationConsent: form.requireAuthorizationConsent,
      requireProofKey: form.requireProofKey,
    }
    client.value = await updateClient(clientId, payload)
  } catch (e) {
    error.value = e instanceof ApiError && e.status === 404 ? 'notFound' : 'invalid'
  } finally {
    saving.value = false
  }
}

async function rotate(): Promise<void> {
  newSecret.value = (await rotateClientSecret(clientId)).clientSecret
}

async function toggle(): Promise<void> {
  if (client.value) {
    client.value = await setClientEnabled(clientId, !client.value.enabled)
  }
}

async function remove(): Promise<void> {
  if (deleteConfirm.value !== clientId) {
    return
  }
  await deleteClient(clientId)
  void router.push('/admin/clients')
}

onMounted(load)
</script>

<template>
  <AdminLayout>
    <RouterLink to="/admin/clients" class="inline-flex items-center gap-1 text-xs text-slate-500 transition hover:text-slate-900">
      ← {{ t('clientAdmin.detail.back') }}
    </RouterLink>

    <header class="mt-2 flex flex-wrap items-center gap-3">
      <h1 class="font-mono text-lg font-semibold text-slate-900 md:text-xl">{{ clientId }}</h1>
      <template v-if="client">
        <span class="inline-flex rounded-full border border-slate-200 bg-white px-2 py-0.5 text-xs text-slate-600">
          {{ t(`clientAdmin.type.${client.type}`) }}
        </span>
        <span
          class="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs"
          :class="client.enabled ? 'bg-emerald-50 text-emerald-700' : 'bg-slate-100 text-slate-500'"
        >
          <span class="h-1.5 w-1.5 rounded-full" :class="client.enabled ? 'bg-emerald-500' : 'bg-slate-400'"></span>
          {{ client.enabled ? t('clientAdmin.enabled') : t('clientAdmin.disabled') }}
        </span>
      </template>
    </header>
    <p class="mt-1 text-sm text-slate-500">{{ t('clientAdmin.detail.subtitle') }}</p>

    <p v-if="error" class="mt-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
      {{ t(`clientAdmin.errors.${error}`) }}
    </p>

    <div v-if="client" class="mt-6 flex flex-col gap-6 lg:flex-row lg:items-start">
      <form class="grid flex-1 gap-4" @submit.prevent="save">
        <section class="rounded-xl border border-slate-200 bg-white p-5">
          <h2 class="text-sm font-semibold text-slate-900">{{ t('clientAdmin.detail.basicTitle') }}</h2>
          <div class="mt-4 grid gap-4 md:grid-cols-2">
            <label class="grid gap-1 text-sm">
              <span class="font-medium text-slate-700">{{ t('clientAdmin.detail.clientName') }}</span>
              <input
                v-model="form.clientName"
                data-field="clientName"
                class="rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none transition focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
              />
            </label>
            <div>
              <span class="text-sm font-medium text-slate-700">Client ID</span>
              <p class="mt-1 flex items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 font-mono text-sm text-slate-500">
                <span class="min-w-0 flex-1 truncate">{{ clientId }}</span>
                <svg class="h-3.5 w-3.5 shrink-0 text-slate-400" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                  <path
                    fill-rule="evenodd"
                    d="M10 1a4.5 4.5 0 0 0-4.5 4.5V9H5a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6a2 2 0 0 0-2-2h-.5V5.5A4.5 4.5 0 0 0 10 1Zm3 8V5.5a3 3 0 1 0-6 0V9h6Z"
                    clip-rule="evenodd"
                  />
                </svg>
              </p>
              <p class="mt-1 text-xs text-slate-400">{{ t('clientAdmin.detail.readonlyHint') }}</p>
            </div>
          </div>
        </section>

        <section class="rounded-xl border border-slate-200 bg-white p-5">
          <h2 class="text-sm font-semibold text-slate-900">{{ t('clientAdmin.detail.redirectTitle') }}</h2>
          <div
            v-if="client.type === 'machine'"
            class="mt-4 rounded-lg border border-dashed border-slate-200 px-4 py-6 text-center text-xs text-slate-500"
          >
            {{ t('clientAdmin.detail.noRedirect') }}
          </div>
          <div v-else class="mt-4 grid gap-4 md:grid-cols-2">
            <label class="grid gap-1 text-sm">
              <span class="font-medium text-slate-700">{{ t('clientAdmin.detail.redirectUris') }}</span>
              <textarea
                v-model="form.redirectUris"
                data-field="redirectUris"
                rows="3"
                class="rounded-lg border border-slate-200 px-3 py-2 font-mono text-xs outline-none transition focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
              />
            </label>
            <label class="grid gap-1 text-sm">
              <span class="font-medium text-slate-700">{{ t('clientAdmin.detail.postLogoutRedirectUris') }}</span>
              <textarea
                v-model="form.postLogoutRedirectUris"
                data-field="postLogoutRedirectUris"
                rows="3"
                class="rounded-lg border border-slate-200 px-3 py-2 font-mono text-xs outline-none transition focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
              />
            </label>
          </div>
        </section>

        <section class="rounded-xl border border-slate-200 bg-white p-5">
          <h2 class="text-sm font-semibold text-slate-900">{{ t('clientAdmin.detail.scopesTitle') }}</h2>
          <label class="mt-4 grid gap-1 text-sm">
            <span class="font-medium text-slate-700">{{ t('clientAdmin.detail.scopes') }}</span>
            <input
              v-model="form.scopes"
              data-field="scopes"
              class="rounded-lg border border-slate-200 px-3 py-2 font-mono text-sm outline-none transition focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            />
          </label>
        </section>

        <section class="rounded-xl border border-slate-200 bg-white p-5">
          <h2 class="text-sm font-semibold text-slate-900">{{ t('clientAdmin.detail.securityTitle') }}</h2>
          <div class="mt-4 grid gap-4">
            <label class="flex items-start gap-2 text-sm">
              <input
                v-model="form.requireProofKey"
                data-field="requireProofKey"
                type="checkbox"
                :disabled="publicClient"
                class="mt-0.5 h-4 w-4 rounded border-slate-300"
              />
              <span>
                <span class="font-medium text-slate-700">{{ t('clientAdmin.detail.requireProofKey') }}</span>
                <span class="block text-xs text-slate-400">{{ t('clientAdmin.detail.proofKeyHint') }}</span>
              </span>
            </label>
            <label class="flex items-start gap-2 text-sm">
              <input
                v-model="form.requireAuthorizationConsent"
                data-field="requireAuthorizationConsent"
                type="checkbox"
                class="mt-0.5 h-4 w-4 rounded border-slate-300"
              />
              <span>
                <span class="font-medium text-slate-700">{{ t('clientAdmin.detail.requireAuthorizationConsent') }}</span>
                <span class="block text-xs text-slate-400">{{ t('clientAdmin.detail.consentHint') }}</span>
              </span>
            </label>
          </div>
        </section>

        <div class="flex items-center justify-end rounded-xl border border-slate-200 bg-white p-4">
          <button
            type="submit"
            data-action="save"
            :disabled="saving"
            class="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500 disabled:opacity-60"
          >
            {{ t('clientAdmin.detail.save') }}
          </button>
        </div>
      </form>

      <aside class="grid w-full gap-4 lg:w-80 lg:shrink-0">
        <section class="rounded-xl border border-slate-200 bg-white p-5">
          <h2 class="text-sm font-semibold text-slate-900">{{ t('clientAdmin.detail.statusTitle') }}</h2>
          <div class="mt-3 flex flex-wrap items-center justify-between gap-2">
            <span
              class="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs"
              :class="client.enabled ? 'bg-emerald-50 text-emerald-700' : 'bg-slate-100 text-slate-500'"
            >
              <span class="h-1.5 w-1.5 rounded-full" :class="client.enabled ? 'bg-emerald-500' : 'bg-slate-400'"></span>
              {{ client.enabled ? t('clientAdmin.enabled') : t('clientAdmin.disabled') }}
            </span>
            <button
              type="button"
              data-action="toggle"
              class="rounded-lg border border-slate-300 px-3 py-1.5 text-xs font-semibold text-slate-700 transition hover:bg-slate-50"
              @click="toggle"
            >
              {{ client.enabled ? t('clientAdmin.detail.disable') : t('clientAdmin.detail.enable') }}
            </button>
          </div>
          <dl class="mt-4 grid gap-2 border-t border-slate-100 pt-3 text-xs">
            <div class="flex items-center justify-between gap-2">
              <dt class="text-slate-500">{{ t('clientAdmin.list.columns.type') }}</dt>
              <dd class="text-slate-800">{{ t(`clientAdmin.type.${client.type}`) }}</dd>
            </div>
            <div class="flex items-center justify-between gap-2">
              <dt class="text-slate-500">{{ t('clientAdmin.detail.createdAt') }}</dt>
              <dd class="text-slate-800">{{ formatDate(client.clientIdIssuedAt) }}</dd>
            </div>
          </dl>
        </section>

        <section class="rounded-xl border border-slate-200 bg-white p-5">
          <h2 class="text-sm font-semibold text-slate-900">{{ t('clientAdmin.detail.secretTitle') }}</h2>
          <p class="mt-1 text-xs text-slate-500">{{ t('clientAdmin.detail.secretHint') }}</p>
          <button
            type="button"
            data-action="rotate"
            class="mt-3 rounded-lg border border-slate-300 px-3 py-1.5 text-xs font-semibold text-slate-700 transition hover:bg-slate-50"
            @click="rotate"
          >
            {{ t('clientAdmin.detail.rotate') }}
          </button>
          <p v-if="newSecret" class="mt-3 break-all rounded-lg border border-amber-300 bg-amber-50 p-3 font-mono text-xs text-amber-900">
            {{ newSecret }}
          </p>
        </section>

        <section class="rounded-xl border border-rose-200 bg-rose-50/50 p-5">
          <h2 class="text-sm font-semibold text-rose-700">{{ t('clientAdmin.detail.dangerTitle') }}</h2>
          <p class="mt-1 text-xs text-rose-600">{{ t('clientAdmin.detail.dangerHint') }}</p>
          <input
            v-model="deleteConfirm"
            data-field="deleteConfirm"
            :placeholder="t('clientAdmin.detail.deletePlaceholder')"
            class="mt-3 w-full rounded-lg border border-rose-300 px-3 py-2 text-xs outline-none transition focus:border-rose-400 focus:ring-2 focus:ring-rose-100"
          />
          <button
            type="button"
            data-action="delete"
            class="mt-2 rounded-lg bg-rose-600 px-3 py-2 text-xs font-semibold text-white transition hover:bg-rose-500"
            @click="remove"
          >
            {{ t('clientAdmin.detail.delete') }}
          </button>
        </section>
      </aside>
    </div>
  </AdminLayout>
</template>
