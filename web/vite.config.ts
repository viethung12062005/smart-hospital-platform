/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';

// Gateway của cụm local (make -C dev port-forward). Trình duyệt gọi cùng origin (/fhir, /api) và Vite chuyển tiếp,
// nên không cần CORS ở gateway khi phát triển.
const GATEWAY = process.env.SHP_GATEWAY_URL ?? 'http://localhost:18000';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    strictPort: true, // khớp redirect URI của client shp-web trong realm Keycloak
    proxy: {
      '/fhir': GATEWAY,
      '/api': GATEWAY,
    },
    // Danh mục mã đọc thẳng từ contracts/terminology (một nguồn duy nhất, không chép tay).
    fs: { allow: ['..'] },
  },
  preview: { port: 4173, strictPort: true },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    css: false,
    testTimeout: 30_000,
    coverage: {
      provider: 'v8',
      include: ['src/**'],
      // Máy chủ giả và điểm khởi động không tính vào coverage (không phải code chạy thật).
      exclude: ['src/**/*.test.*', 'src/test/**', 'src/mocks/**', 'src/main.tsx', 'src/vite-env.d.ts'],
      reporter: ['text-summary', 'html'],
      // Cùng ngưỡng với backend (Definition of Done): coverage dòng >= 70%.
      thresholds: { lines: 70, statements: 70, functions: 60, branches: 60 },
    },
  },
});
