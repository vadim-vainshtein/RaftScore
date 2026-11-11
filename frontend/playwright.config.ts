import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  workers: 1,
  use: { baseURL: process.env.RAFTSCORE_E2E_URL, viewport: { width: 768, height: 900 }, channel: 'chrome' },
})
