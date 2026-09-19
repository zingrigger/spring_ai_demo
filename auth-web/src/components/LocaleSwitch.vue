<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { setLocale, type Locale } from '../i18n'

const { locale } = useI18n()
const current = computed(() => locale.value as Locale)
const options: { value: Locale; label: string }[] = [
  { value: 'zh', label: '中文' },
  { value: 'en', label: 'EN' },
]

function switchTo(next: Locale): void {
  if (next !== current.value) setLocale(next)
}
</script>

<template>
  <div class="inline-flex overflow-hidden rounded-full border border-slate-200 text-xs">
    <button
      v-for="option in options"
      :key="option.value"
      type="button"
      :data-locale="option.value"
      class="px-3 py-1 transition"
      :class="option.value === current
        ? 'bg-indigo-600 text-white'
        : 'bg-white text-slate-500 hover:bg-slate-50'"
      @click="switchTo(option.value)"
    >
      {{ option.label }}
    </button>
  </div>
</template>
