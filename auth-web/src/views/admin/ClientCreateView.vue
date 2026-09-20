<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import AuthLayout from '../../components/AuthLayout.vue'
import { ApiError } from '../../api/client'
import { createClient, type CreateClientPayload } from '../../api/adminClients'

const { t } = useI18n()
const router = useRouter()

const form = reactive({
  clientId: '',
  clientName: '',
  type: 'web' as CreateClientPayload['type'],
  redirectUris: '',
  postLogoutRedirectUris: '',
  scopes: '',
})

const error = ref<string | null>(null)
const submitting = ref(false)
const created = ref<{ clientId: string; clientSecret: string | null } | null>(null)

function splitLines(value: string): string[] {
  return value.split('\n').map((line) => line.trim()).filter((line) => line.length > 0)
}

function splitScopes(value: string): string[] {
  return value.split(/[\s,]+/).map((scope) => scope.trim()).filter((scope) => scope.length > 0)
}

async function submit(): Promise<void> {
  error.value = null
  submitting.value = true
  try {
    const payload: CreateClientPayload = {
      clientId: form.clientId.trim(),
      clientName: form.clientName.trim(),
      type: form.type,
      redirectUris: splitLines(form.redirectUris),
      postLogoutRedirectUris: splitLines(form.postLogoutRedirectUris),
      scopes: splitScopes(form.scopes),
      requireAuthorizationConsent: form.type !== 'machine',
    }
    const result = await createClient(payload)
    created.value = { clientId: result.client.clientId, clientSecret: result.clientSecret }
  } catch (e) {
    error.value = e instanceof ApiError && e.code === 'client_id_taken' ? 'clientIdTaken' : 'invalid'
  } finally {
    submitting.value = false
  }
}

async function copySecret(): Promise<void> {
  if (created.value?.clientSecret) {
    await navigator.clipboard?.writeText(created.value.clientSecret)
  }
}

function finish(): void {
  if (created.value) {
    void router.push(`/admin/clients/${encodeURIComponent(created.value.clientId)}`)
  }
}
</script>

<template>
  <AuthLayout :title="t('clientAdmin.create.title')" :subtitle="t('clientAdmin.create.subtitle')">
    <form class="mt-6 grid gap-4" @submit.prevent="submit">
      <label class="grid gap-1 text-sm">
        <span class="font-semibold text-slate-700">{{ t('clientAdmin.create.clientId') }}</span>
        <input v-model="form.clientId" data-field="clientId" required
               class="rounded-lg border border-slate-300 px-3 py-2" />
      </label>
      <label class="grid gap-1 text-sm">
        <span class="font-semibold text-slate-700">{{ t('clientAdmin.create.clientName') }}</span>
        <input v-model="form.clientName" data-field="clientName" required
               class="rounded-lg border border-slate-300 px-3 py-2" />
      </label>
      <label class="grid gap-1 text-sm">
        <span class="font-semibold text-slate-700">{{ t('clientAdmin.create.type') }}</span>
        <select v-model="form.type" data-field="type" class="rounded-lg border border-slate-300 px-3 py-2">
          <option value="web">{{ t('clientAdmin.type.web') }}</option>
          <option value="machine">{{ t('clientAdmin.type.machine') }}</option>
          <option value="public">{{ t('clientAdmin.type.public') }}</option>
        </select>
      </label>
      <label v-if="form.type !== 'machine'" class="grid gap-1 text-sm">
        <span class="font-semibold text-slate-700">{{ t('clientAdmin.create.redirectUris') }}</span>
        <textarea v-model="form.redirectUris" data-field="redirectUris" rows="2"
                  class="rounded-lg border border-slate-300 px-3 py-2" />
      </label>
      <label v-if="form.type !== 'machine'" class="grid gap-1 text-sm">
        <span class="font-semibold text-slate-700">{{ t('clientAdmin.create.postLogoutRedirectUris') }}</span>
        <textarea v-model="form.postLogoutRedirectUris" data-field="postLogoutRedirectUris" rows="2"
                  class="rounded-lg border border-slate-300 px-3 py-2" />
      </label>
      <label class="grid gap-1 text-sm">
        <span class="font-semibold text-slate-700">{{ t('clientAdmin.create.scopes') }}</span>
        <input v-model="form.scopes" data-field="scopes" required
               class="rounded-lg border border-slate-300 px-3 py-2" />
      </label>

      <p v-if="error" class="text-sm text-rose-600">{{ t(`clientAdmin.errors.${error}`) }}</p>

      <button type="submit" :disabled="submitting"
              class="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500 disabled:opacity-60">
        {{ submitting ? t('clientAdmin.create.submitting') : t('clientAdmin.create.submit') }}
      </button>
    </form>

    <div v-if="created" class="mt-6 rounded-lg border border-amber-300 bg-amber-50 p-4">
      <p class="text-sm font-semibold text-amber-900">{{ t('clientAdmin.create.secretTitle') }}</p>
      <p class="mt-2 break-all font-mono text-sm text-amber-900">{{ created.clientSecret }}</p>
      <div class="mt-3 flex gap-2">
        <button type="button" data-action="copy" class="rounded-lg border border-amber-400 px-3 py-1 text-sm"
                @click="copySecret">{{ t('clientAdmin.create.copy') }}</button>
        <button type="button" data-action="finish" class="rounded-lg bg-amber-600 px-3 py-1 text-sm text-white"
                @click="finish">{{ t('clientAdmin.create.saved') }}</button>
      </div>
    </div>
  </AuthLayout>
</template>
