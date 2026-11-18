/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the Vite server is the single public origin (http://localhost:5173) and proxies
// BFF paths to Spring Boot, so the session and CSRF cookies are first-party and no CORS is needed.
// In Docker, nginx plays the same role (see nginx.conf).
const backend = process.env.PAYASSIST_BACKEND_URL ?? 'http://localhost:8080';
const proxied = ['/api', '/oauth2', '/login/oauth2', '/logout', '/actuator'];

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: Object.fromEntries(proxied.map((path) => [path, { target: backend, changeOrigin: false }])),
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'], // browser tests in e2e/ run with Playwright (npm run e2e)
    css: false,
  },
});
