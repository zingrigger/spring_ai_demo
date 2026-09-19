<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AuthLayout from '../components/AuthLayout.vue'
import { login } from '../api/auth'
import { ApiError } from '../api/client'
import { navigate } from '../navigation'

const { t } = useI18n()
const account = ref('')
const password = ref('')
const error = ref<string | null>(null)
const submitting = ref(false)

async function submit(): Promise<void> {
  error.value = null
  submitting.value = true
  try {
    const result = await login(account.value, password.value)
    navigate(result.next)
  } catch (cause) {
    error.value = cause instanceof ApiError && cause.status === 401 ? t('login.invalid') : t('login.failed')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthLayout :title="t('login.title')" :subtitle="t('login.subtitle')">
    <form class="mt-6 grid gap-4" @submit.prevent="submit">
      <p v-if="error" role="alert"
         class="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
        {{ error }}
      </p>
      <label class="grid gap-1 text-sm font-medium text-slate-700">
        {{ t('login.account') }}
        <input v-model="account" name="account" autocomplete="username" required autofocus
               class="rounded-lg border border-slate-200 px-3 py-2 font-normal text-slate-900 outline-none focus:ring-2 focus:ring-indigo-500" />
      </label>
      <label class="grid gap-1 text-sm font-medium text-slate-700">
        {{ t('login.password') }}
        <input v-model="password" name="password" type="password" autocomplete="current-password" required
               class="rounded-lg border border-slate-200 px-3 py-2 font-normal text-slate-900 outline-none focus:ring-2 focus:ring-indigo-500" />
      </label>
      <button type="submit" :disabled="submitting"
              class="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500 disabled:opacity-60">
        {{ submitting ? t('login.submitting') : t('login.submit') }}
      </button>
    </form>
  </AuthLayout>
</template>
