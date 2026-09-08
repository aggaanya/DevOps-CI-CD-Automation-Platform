import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Additional HTML entry for the OIDC silent-renew iframe callback. It is a
// fallback renew path (oidc-client-ts normally refreshes via the refresh
// token); keeping it registered keeps signinSilent() usable even if Keycloak
// ever stops returning refresh tokens for the public client.
const silentRenewEntry = fileURLToPath(new URL('./silent-renew.html', import.meta.url))

export default defineConfig(() => ({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        // Windows/macOS local Vite runs outside Docker, so service DNS names
        // such as `backend` are not resolvable there. Docker Compose serves
        // the production frontend through nginx, which continues to use
        // backend:8081 via BACKEND_INTERNAL_HOST.
        target: process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
  build: {
    rollupOptions: {
      input: {
        main: fileURLToPath(new URL('./index.html', import.meta.url)),
        'silent-renew': silentRenewEntry,
      },
    },
  },
}))
