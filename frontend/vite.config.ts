import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import pkg from './package.json'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // The release version (bumped with every change, see CHANGELOG.md), shown in the sidebar.
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
})
