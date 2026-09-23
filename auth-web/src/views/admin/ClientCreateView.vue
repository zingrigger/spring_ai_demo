<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import AdminLayout from '../../components/AdminLayout.vue'
import { ApiError } from '../../api/client'
import { createClient, type CreateClientPayload, type ClientType } from '../../api/adminClients'

const { t } = useI18n()
const router = useRouter()

const typeOptions: ClientType[] = ['web', 'machine', 'public']

const commonScopes = computed(() => [
  { name: 'openid', description: null },
  { name: 'profile', description: t('scope.profile') },
  { name: 'weather:read', description: t('scope.weatherRead') },
])

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
  <AdminLayout>
    <RouterLink to="/admin/clients" class="inline-flex items-center gap-1 text-xs text-slate-500 transition hover:text-slate-900">
      ← {{ t('clientAdmin.create.back') }}
    </RouterLink>
    <header class="mt-2">
      <h1 class="text-xl font-semibold text-slate-900 md:text-2xl">{{ t('clientAdmin.create.title') }}</h1>
      <p class="mt-1 text-sm text-slate-500">{{ t('clientAdmin.create.subtitle') }}</p>
    </header>

    <form class="mt-6 grid gap-4" @submit.prevent="submit">
      <section class="rounded-xl border border-slate-200 bg-white p-5">
        <h2 class="text-sm font-semibold text-slate-900">{{ t('clientAdmin.create.basicTitle') }}</h2>
        <div class="mt-4 grid gap-4 md:grid-cols-2">
          <label class="grid gap-1 text-sm">
            <span class="font-medium text-slate-700">{{ t('clientAdmin.create.clientId') }}</span>
            <input
              v-model="form.clientId"
              data-field="clientId"
              required
              placeholder="weather-web"
              class="rounded-lg border border-slate-200 px-3 py-2 font-mono text-sm outline-none transition focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            />
          </label>
          <label class="grid gap-1 text-sm">
            <span class="font-medium text-slate-700">{{ t('clientAdmin.create.clientName') }}</span>
            <input
              v-model="form.clientName"
              data-field="clientName"
              required
              placeholder="Weather Web"
              class="rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none transition focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            />
          </label>
        </div>

        <fieldset class="mt-5">
          <legend class="text-sm font-medium text-slate-700">{{ t('clientAdmin.create.type') }}</legend>
          <div class="mt-2 grid gap-3 md:grid-cols-3">
            <label
              v-for="option in typeOptions"
              :key="option"
              class="cursor-pointer rounded-lg border p-3 transition"
              :class="form.type === option
                ? 'border-indigo-500 bg-indigo-50/60 ring-1 ring-indigo-200'
                : 'border-slate-200 hover:border-slate-300'"
            >
              <input
                type="radio"
                name="type"
                class="sr-only"
                :value="option"
                v-model="form.type"
                :data-field="`type-${option}`"
              />
              <span class="flex items-center gap-2 text-sm font-semibold text-slate-900">
                <span
                  class="flex h-3.5 w-3.5 items-center justify-center rounded-full border"
                  :class="form.type === option ? 'border-indigo-600' : 'border-slate-300'"
                >
                  <span v-if="form.type === option" class="h-1.5 w-1.5 rounded-full bg-indigo-600"></span>
                </span>
                {{ t(`clientAdmin.type.${option}`) }}
              </span>
              <span class="mt-1 block text-xs leading-relaxed text-slate-500">
                {{ t(`clientAdmin.create.typeHints.${option}`) }}
              </span>
            </label>
          </div>
        </fieldset>
      </section>

      <section v-if="form.type !== 'machine'" class="rounded-xl border border-slate-200 bg-white p-5">
        <h2 class="text-sm font-semibold text-slate-900">{{ t('clientAdmin.create.redirectTitle') }}</h2>
        <div class="mt-4 grid gap-4 md:grid-cols-2">
          <label class="grid gap-1 text-sm">
            <span class="font-medium text-slate-700">{{ t('clientAdmin.create.redirectUris') }}</span>
            <textarea
              v-model="form.redirectUris"
              data-field="redirectUris"
              rows="3"
              placeholder="https://app.example.com/callback"
              class="rounded-lg border border-slate-200 px-3 py-2 font-mono text-xs outline-none transition focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            />
          </label>
          <label class="grid gap-1 text-sm">
            <span class="font-medium text-slate-700">{{ t('clientAdmin.create.postLogoutRedirectUris') }}</span>
            <textarea
              v-model="form.postLogoutRedirectUris"
              data-field="postLogoutRedirectUris"
              rows="3"
              placeholder="https://app.example.com/"
              class="rounded-lg border border-slate-200 px-3 py-2 font-mono text-xs outline-none transition focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            />
          </label>
        </div>
        <p class="mt-3 text-xs text-slate-500">{{ t('clientAdmin.create.uriHint') }}</p>
      </section>

      <section class="rounded-xl border border-slate-200 bg-white p-5">
        <h2 class="text-sm font-semibold text-slate-900">{{ t('clientAdmin.create.scopesTitle') }}</h2>
        <label class="mt-4 grid gap-1 text-sm">
          <span class="font-medium text-slate-700">{{ t('clientAdmin.create.scopes') }}</span>
          <input
            v-model="form.scopes"
            data-field="scopes"
            required
            placeholder="openid profile weather:read"
            class="rounded-lg border border-slate-200 px-3 py-2 font-mono text-sm outline-none transition focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
          />
        </label>
        <p class="mt-3 flex flex-wrap items-center gap-1.5 text-xs text-slate-500">
          {{ t('clientAdmin.create.scopeHint') }}
          <span
            v-for="scope in commonScopes"
            :key="scope.name"
            class="rounded bg-indigo-50 px-1.5 py-0.5 font-mono text-[0.7rem] text-indigo-700"
            :title="scope.description ?? undefined"
          >
            {{ scope.name }}
          </span>
        </p>
      </section>

      <p v-if="error" class="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
        {{ t(`clientAdmin.errors.${error}`) }}
      </p>

      <div class="flex items-center justify-end gap-2 rounded-xl border border-slate-200 bg-white p-4">
        <RouterLink
          to="/admin/clients"
          class="rounded-lg border border-slate-300 px-4 py-2 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
        >
          {{ t('clientAdmin.create.cancel') }}
        </RouterLink>
        <button
          type="submit"
          :disabled="submitting"
          class="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500 disabled:opacity-60"
        >
          {{ submitting ? t('clientAdmin.create.submitting') : t('clientAdmin.create.submit') }}
        </button>
      </div>
    </form>

    <div v-if="created" class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/45 p-4">
      <div class="w-full max-w-md rounded-xl border border-amber-200 bg-white p-6 shadow-2xl">
        <h2 class="flex items-center gap-2 text-base font-semibold text-slate-900">
          <span class="text-amber-500" aria-hidden="true">⚠</span>
          {{ t('clientAdmin.create.secretTitle') }}
        </h2>
        <p class="mt-1 text-sm text-slate-600">
          {{ created.clientSecret ? t('clientAdmin.create.secretHint') : t('clientAdmin.create.noSecret') }}
        </p>
        <div v-if="created.clientSecret" class="mt-4 rounded-lg border border-amber-300 bg-amber-50 p-3">
          <p class="break-all font-mono text-sm text-amber-900">{{ created.clientSecret }}</p>
        </div>
        <div class="mt-5 flex flex-wrap justify-end gap-2">
          <button
            v-if="created.clientSecret"
            type="button"
            data-action="copy"
            class="rounded-lg border border-amber-400 px-3 py-2 text-sm text-amber-800 transition hover:bg-amber-50"
            @click="copySecret"
          >
            {{ t('clientAdmin.create.copy') }}
          </button>
          <button
            type="button"
            data-action="finish"
            class="rounded-lg bg-amber-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-amber-500"
            @click="finish"
          >
            {{ t('clientAdmin.create.saved') }}
          </button>
        </div>
      </div>
    </div>
  </AdminLayout>
</template>
