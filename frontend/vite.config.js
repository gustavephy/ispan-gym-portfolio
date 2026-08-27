import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // axios 的 baseURL 沒設環境變數時打相對路徑，開發時由這裡代理到後端
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // 後台上傳的商品／課程圖片存在後端的 uploads 目錄，image_url 存的是 /uploads/xxx
      // 這種相對路徑；沒有這條代理的話瀏覽器會拿它去問 dev server（5173）而得到 404
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // 聊天室 SockJS 交握＋WebSocket 升級都走這個前綴，ws:true 讓 proxy 也處理升級請求
      '/ws-chat': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
    },
  },
})
