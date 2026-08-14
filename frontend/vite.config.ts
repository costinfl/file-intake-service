/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// This is a project (non-user/org) GitHub Pages site, served under /file-intake-service/,
// not the root — every asset reference must resolve relative to that base path.
export default defineConfig({
  base: '/file-intake-service/',
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/tests/setup.ts'],
  },
})
