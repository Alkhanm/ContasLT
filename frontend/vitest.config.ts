import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'

// Testes do domínio e da camada de dados: Node puro, IndexedDB falso (fake-indexeddb).
export default defineConfig({
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
    setupFiles: ['fake-indexeddb/auto'],
  },
})
