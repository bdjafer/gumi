import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['simulation/**/*.test.ts'],
  },
})
