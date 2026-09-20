<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import AuthLayout from '../../components/AuthLayout.vue'
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

const { t } = useI18n()
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
})

function splitLines(value: string): string[] {
  return value.split('\n').map((line) => line.trim()).filter((line) => line.length > 0)
}

function splitScopes(value: string): string[] {
  return value.split(/[\s,]+/).map((scope) => scope.trim()).filter((scope) => scope.length > 0)
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
  <AuthLayout :title="t('clientAdmin.detail.title', { clientId })" :subtitle="t('clientAdmin.detail.subtitle')">
    <p v-if="error" class="mt-4 text-sm text-rose-600">{{ t(`clientAdmin.errors.${error}`) }}</p>

    <template v-if="client">
      <dl class="mt-4 grid gap-2 rounded-lg border border-slate-200 p-4 text-sm">
        <div class="flex items-center justify-between gap-2">
          <dt class="text-xs uppercase tracking-wide text-slate-500">{{ t('clientAdmin.detail.currentName') }}</dt>
          <dd class="font-semibold text-slate-900">{{ client.clientName }}</dd>
        </div>
        <div class="flex items-center justify-between gap-2">
          <dt class="text-xs uppercase tracking-wide text-slate-500">{{ t('clientAdmin.list.columns.type') }}</dt>
          <dd class="text-slate-700">{{ t(`clientAdmin.type.${client.type}`) }}</dd>
        </div>
        <div class="flex items-center justify-between gap-2">
          <dt class="text-xs uppercase tracking-wide text-slate-500">{{ t('clientAdmin.list.columns.status') }}</dt>
          <dd :class="client.enabled ? 'text-emerald-700' : 'text-rose-700'">
            {{ client.enabled ? t('clientAdmin.enabled') : t('clientAdmin.disabled') }}
          </dd>
        </div>
      </dl>
      <form class="mt-6 grid gap-4" @submit.prevent="save">
        <label class="grid gap-1 text-sm">
          <span class="font-semibold text-slate-700">{{ t('clientAdmin.detail.clientName') }}</span>
          <input v-model="form.clientName" data-field="clientName"
                 class="rounded-lg border border-slate-300 px-3 py-2" />
        </label>
        <label class="grid gap-1 text-sm">
          <span class="font-semibold text-slate-700">{{ t('clientAdmin.detail.redirectUris') }}</span>
          <textarea v-model="form.redirectUris" data-field="redirectUris" rows="2"
                    class="rounded-lg border border-slate-300 px-3 py-2" />
        </label>
        <label class="grid gap-1 text-sm">
          <span class="font-semibold text-slate-700">{{ t('clientAdmin.detail.postLogoutRedirectUris') }}</span>
          <textarea v-model="form.postLogoutRedirectUris" data-field="postLogoutRedirectUris" rows="2"
                    class="rounded-lg border border-slate-300 px-3 py-2" />
        </label>
        <label class="grid gap-1 text-sm">
          <span class="font-semibold text-slate-700">{{ t('clientAdmin.detail.scopes') }}</span>
          <input v-model="form.scopes" data-field="scopes"
                 class="rounded-lg border border-slate-300 px-3 py-2" />
        </label>
        <button type="submit" data-action="save" :disabled="saving"
                class="justify-self-start rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500 disabled:opacity-60">
          {{ t('clientAdmin.detail.save') }}
        </button>
      </form>

      <section class="mt-8 rounded-lg border border-rose-200 p-4">
        <h2 class="text-sm font-semibold text-rose-700">{{ t('clientAdmin.detail.dangerTitle') }}</h2>
        <p class="mt-2 text-xs text-rose-600">{{ t('clientAdmin.detail.dangerHint') }}</p>

        <div class="mt-4 flex flex-wrap items-center gap-2">
          <button type="button" data-action="rotate"
                  class="rounded-lg border border-rose-300 px-3 py-1 text-sm text-rose-700"
                  @click="rotate">{{ t('clientAdmin.detail.rotate') }}</button>
          <button type="button" data-action="toggle"
                  class="rounded-lg border border-rose-300 px-3 py-1 text-sm text-rose-700"
                  @click="toggle">
            {{ client.enabled ? t('clientAdmin.detail.disable') : t('clientAdmin.detail.enable') }}
          </button>
        </div>

        <p v-if="newSecret" class="mt-3 break-all rounded-lg bg-rose-50 p-3 font-mono text-sm text-rose-800">
          {{ newSecret }}
        </p>

        <div class="mt-4 flex flex-wrap items-center gap-2">
          <input v-model="deleteConfirm" data-field="deleteConfirm"
                 :placeholder="t('clientAdmin.detail.deletePlaceholder')"
                 class="rounded-lg border border-rose-300 px-3 py-1 text-sm" />
          <button type="button" data-action="delete"
                  class="rounded-lg bg-rose-600 px-3 py-1 text-sm font-semibold text-white"
                  @click="remove">{{ t('clientAdmin.detail.delete') }}</button>
        </div>
      </section>
    </template>
  </AuthLayout>
</template>
