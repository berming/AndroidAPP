import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite config for admin SPA.
 *
 * - `base: '/admin/'`：Caddyfile 用 `handle_path /admin/*` 剥前缀后交给静态文件
 *   服务；Vue Router 也走 history mode + `/admin/` base
 * - dev proxy：本地 `npm run dev` 把 `/admin/api/*` / `/admin-auth/*` 转给 :8080
 *   Ktor，避免跨域配置
 * - 简单路线：app.use(ElementPlus) 全量注册（gzipped ~600 KB），不上 on-demand
 *   import；CI 的 dist 大小看门狗（5 MB）足够防全量回归
 */
export default defineConfig({
  base: '/admin/',
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/admin/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/admin-auth': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1024,
    reportCompressedSize: true,
  },
})
