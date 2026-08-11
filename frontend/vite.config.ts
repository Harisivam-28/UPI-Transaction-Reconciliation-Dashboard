import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// When running inside Docker Compose, VITE_API_TARGET is set to http://app:8080.
// When running locally (npm run dev), it falls back to http://localhost:8080.
const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  define: {
    // sockjs-client references `global` which doesn't exist in ESM.
    global: 'globalThis',
  },
  server: {
    port: 5173,
    host: '0.0.0.0',        // needed for Docker: listen on all interfaces
    allowedHosts: true,      // accept requests from any host header
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
        secure: false,
        headers: {
          'Origin': 'http://localhost:5173'
        }
      },
      '/ws': {
        target: apiTarget,
        changeOrigin: true,
        secure: false,
        ws: true,
      },
    },
  },
})
