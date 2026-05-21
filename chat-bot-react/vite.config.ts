import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const rawBasePath = process.env.APP_BASE_PATH  || '/voice-poc/fe/';
const base = rawBasePath === '/' ? '/' : `/${rawBasePath.replace(/^\/+|\/+$/g, '')}/`

// https://vite.dev/config/
export default defineConfig({
  base,
  plugins: [react()],
  server: {
    allowedHosts: true,
  },
})
