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
      // Google 登入按鈕是整頁導向 /oauth2/authorization/google（不是 XHR）。
      // 後端在這一步建立 IF_REQUIRED 的 OAuth session（JSESSIONID，Path=/，無 Domain），
      // 之後 Google 回呼是直接打 http://localhost:8080/login/oauth2/code/google
      //（Google Console 註冊的絕對網址，不經這個 proxy），cookie 因為不分 port 仍能對上。
      // 少了這條，按鈕在 5173 會 404。
      '/oauth2': {
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
