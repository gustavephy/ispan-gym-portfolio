<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth.js'
import { exchangeCode, googleLoginUrl, sanitizeReturnPath } from '@/api/oauth.js'
import OperationMessage from '@/components/OperationMessage.vue'

/**
 * Google 登入成功後的落地頁。對應後端 app.oauth.frontend-success-url，
 * 後端會把瀏覽器導到 /oauth/callback?code=<一次性交換碼>。
 *
 * 流程：進頁先把網址列的 code 清掉（history.replaceState，縮短一次性碼留在
 * 瀏覽器歷史／截圖／分析工具裡的時間）→ POST /api/auth/oauth/exchange 換 JWT →
 * 寫入既有 auth store → 依後端回傳、已驗證過的 returnPath 導頁（沒有就回首頁）。
 *
 * 交換碼是單次使用：失敗（含使用者按上一頁重載本頁）一律不自動重試，只提供
 * 「重新用 Google 登入」。錯誤訊息用固定中文文案，不直接顯示後端／瀏覽器原始字串。
 */
const router = useRouter()
const auth = useAuthStore()

const status = ref('loading') // 'loading' | 'error'
const errorMessage = ref('')

const CODE_INVALID_MESSAGE = '登入驗證碼無效或已過期，請重新使用 Google 登入。'
const GENERIC_ERROR_MESSAGE = 'Google 登入服務暫時無法使用，請稍後再試。'

onMounted(async () => {
  const params = new URLSearchParams(window.location.search)
  const code = params.get('code')

  // 不論後續成功與否，先把 code 從網址列拿掉
  window.history.replaceState({}, '', window.location.pathname)

  if (!code) {
    status.value = 'error'
    errorMessage.value = '登入連結無效或已失效，請重新使用 Google 登入。'
    return
  }

  try {
    const { data } = await exchangeCode(code)
    auth.setSession(data)
    router.replace(sanitizeReturnPath(data.returnPath) ?? '/')
  } catch (e) {
    status.value = 'error'
    // 後端對「不存在／已使用／已過期／帳號已不可登入」一律回 400 OAUTH_EXCHANGE_CODE_INVALID；
    // 其餘（網路、逾時、500、未知）都用同一句通用文案，不外洩技術訊息。
    errorMessage.value = e.code === 'OAUTH_EXCHANGE_CODE_INVALID' ? CODE_INVALID_MESSAGE : GENERIC_ERROR_MESSAGE
  }
})

function retryWithGoogle() {
  window.location.href = googleLoginUrl()
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-card">
      <template v-if="status === 'loading'">
        <div class="spinner"></div>
        <p class="auth-card__subtitle" role="status" aria-live="polite">
          正在完成 Google 登入，請稍候…
        </p>
      </template>

      <template v-else>
        <div role="alert">
          <h1 class="auth-card__title">登入失敗</h1>
          <OperationMessage :message="errorMessage" tone="error" />
        </div>
        <button class="btn btn-accent btn-block" type="button" @click="retryWithGoogle">
          重新使用 Google 登入
        </button>
        <div class="auth-card__links">
          <RouterLink to="/login">改用帳號密碼登入</RouterLink>
          <RouterLink to="/">返回首頁</RouterLink>
        </div>
      </template>
    </section>
  </main>
</template>
