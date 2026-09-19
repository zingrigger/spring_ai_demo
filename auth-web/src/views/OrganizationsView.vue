<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AuthLayout from '../components/AuthLayout.vue'
import { listOrganizations, selectOrganization, type OrganizationView } from '../api/auth'
import { ApiError } from '../api/client'
import { navigate } from '../navigation'

const { t } = useI18n()
const organizations = ref<OrganizationView[]>([])
const selected = ref<number | null>(null)
const error = ref<string | null>(null)
const submitting = ref(false)

onMounted(async () => {
  try {
    const result = await listOrganizations()
    if (result.next) {
      navigate(result.next)
      return
    }
    organizations.value = result.organizations
    selected.value = result.organizations[0]?.id ?? null
  } catch (cause) {
    error.value = cause instanceof ApiError && cause.status === 403
      ? t('organizations.denied')
      : t('organizations.failed')
  }
})

async function submit(): Promise<void> {
  if (selected.value === null) return
  submitting.value = true
  try {
    const result = await selectOrganization(selected.value)
    navigate(result.next)
  } catch {
    error.value = t('organizations.failed')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthLayout :title="t('organizations.title')" :subtitle="t('organizations.subtitle')">
    <form class="mt-6 grid gap-4" @submit.prevent="submit">
      <p v-if="error" role="alert"
         class="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
        {{ error }}
      </p>
      <label v-for="organization in organizations" :key="organization.id"
             class="flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-2 text-sm"
             :class="organization.id === selected
               ? 'border-indigo-600 bg-indigo-50 text-indigo-900'
               : 'border-slate-200 text-slate-700'">
        <input v-model="selected" type="radio" name="orgId" :value="organization.id" class="sr-only" />
        <span class="h-2 w-2 rounded-full"
              :class="organization.id === selected ? 'bg-indigo-600' : 'border border-slate-300 bg-white'"></span>
        {{ organization.name }}
      </label>
      <button type="submit" :disabled="submitting || selected === null"
              class="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500 disabled:opacity-60">
        {{ t('organizations.submit') }}
      </button>
    </form>
  </AuthLayout>
</template>
