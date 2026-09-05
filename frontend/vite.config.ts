import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

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
}))
