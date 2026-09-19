import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    proxy: {
      '/api': 'http://localhost:8083',
      '/oauth2': 'http://localhost:8083',
      '/.well-known': 'http://localhost:8083',
      '/userinfo': 'http://localhost:8083',
      '/actuator': 'http://localhost:8083',
    },
  },
})
