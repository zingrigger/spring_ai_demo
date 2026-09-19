<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import AuthLayout from '../components/AuthLayout.vue'
import { getConsent, type ConsentState } from '../api/auth'
import { submitConsent } from '../consentForm'

const route = useRoute()
const { t } = useI18n()
const consent = ref<ConsentState | null>(null)
const approved = ref<string[]>([])
const error = ref<string | null>(null)

onMounted(async () => {
  const clientId = String(route.query.client_id ?? '')
  const rawScope = route.query.scope
  const scopes = (Array.isArray(rawScope) ? rawScope : rawScope ? [rawScope] : []).map(String)
  const state = route.query.state ? String(route.query.state) : null
  try {
    const result = await getConsent({ clientId, scopes, state })
    consent.value = result
    approved.value = [...result.scopes]
  } catch {
    error.value = t('consent.failed')
  }
})

function scopeLabel(scope: string): string {
  if (scope === 'profile') return t('scope.profile')
  if (scope === 'weather:read') return t('scope.weatherRead')
  return scope
}

function approve(): void {
  if (!consent.value) return
  submitConsent({
    clientId: consent.value.clientId,
    state: consent.value.state,
    scopes: approved.value,
  })
}
</script>

<template>
  <AuthLayout :title="t('consent.title')"
              :subtitle="consent ? t('consent.subtitle', { client: consent.clientName }) : undefined">
    <p v-if="error" role="alert"
       class="mt-6 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
      {{ error }}
    </p>
    <form v-else-if="consent" class="mt-6 grid gap-4" @submit.prevent="approve">
      <p v-if="consent.organization"
         class="rounded-full bg-indigo-50 px-3 py-1 text-xs font-medium text-indigo-700">
        {{ t('consent.asOrganization', { organization: consent.organization.name }) }}
      </p>
      <fieldset class="grid gap-2">
        <label v-for="scope in consent.scopes" :key="scope"
               class="flex items-center gap-3 text-sm text-slate-700">
          <input v-model="approved" type="checkbox" name="scope" :value="scope"
                 class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
          <span>{{ scopeLabel(scope) }}</span>
        </label>
      </fieldset>
      <button type="submit"
              class="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500">
        {{ t('consent.approve') }}
      </button>
    </form>
  </AuthLayout>
</template>
