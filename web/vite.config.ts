/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';

// Local cluster gateway (make -C dev port-forward). The browser calls the same origin (/fhir, /api) and Vite forwards,
// so no CORS is needed on the gateway during development.
const GATEWAY = process.env.SHP_GATEWAY_URL ?? 'http://localhost:18000';

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  // `npm run dev:mock` (vite --mode mock): enables MSW + mock login. Set here instead of a .env.mock file because the repo ignores all .env.*.
  define: mode === 'mock' ? { 'import.meta.env.VITE_MOCK': JSON.stringify('true') } : {},
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    strictPort: true, // matches the shp-web client's redirect URI in the Keycloak realm
    proxy: {
      '/fhir': GATEWAY,
      '/api': GATEWAY,
    },
    // Code catalogs are read directly from contracts/terminology (a single source, not copied by hand).
    fs: { allow: ['..'] },
  },
  preview: { port: 4173, strictPort: true },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'], // e2e/ holds Playwright tests, run with `npm run e2e`
    css: false,
    testTimeout: 30_000,
    coverage: {
      provider: 'v8',
      include: ['src/**'],
      // The mock server and the entry point are excluded from coverage (they are not real runtime code).
      exclude: ['src/**/*.test.*', 'src/test/**', 'src/mocks/**', 'src/main.tsx', 'src/vite-env.d.ts'],
      reporter: ['text-summary', 'html'],
      // Same thresholds as the backend (Definition of Done): line coverage >= 70%.
      thresholds: { lines: 70, statements: 70, functions: 60, branches: 60 },
    },
  },
}));
