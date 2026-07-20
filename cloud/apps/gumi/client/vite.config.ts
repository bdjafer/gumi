import tailwindcss from '@tailwindcss/vite'
import viteReact from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

export default defineConfig({
  base: '/ui/',
  plugins: [viteReact(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: {
    outDir: '../.dist',
    emptyOutDir: true,
    sourcemap: false,
  },
  // `pnpm dev:hmr` — set `VIEW_DEV_URL=http://127.0.0.1:5173` in the worker's
  // `.dev.vars` to proxy `/ui/*` here and get React HMR.
  server: {
    host: '127.0.0.1',
    port: 5173,
    cors: true,
  },
})
