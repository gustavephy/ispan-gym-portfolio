<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { notify } from '@/api/error-bus.js'

/**
 * Google「綁定」流程的落地頁。對應後端 app.oauth.frontend-link-result-url，
 * 後端會把瀏覽器導到 /account/oauth/callback?result=linked 或 ?oauthError=<白名單 reason>。
 *
 * 這頁刻意很單純：只解析固定白名單結果 → 用 notify() 保存一則跨導頁訊息 →
 * router.replace 回帳號安全頁。綁定狀態的權威來源是帳號安全頁重新掛載時的
 * getOAuthConnections()，不在這裡查——就算後端說 result=linked，也以重新查詢為準。
 *
 * 不讀取、不傳遞 accountId／email／JWT／state。網址列的 query 進頁就清掉。
 */
const router = useRouter()

// LINK 情境的文案。未列出的 reason 用通用 fallback。
const LINK_ERROR_MESSAGE = {
  access_denied: '您已取消 Google 綁定。',
  link_email_mismatch:
    '綁定失敗：Google 帳號的 Email 必須與本站帳號相同，且需為已驗證的 Google Email。',
  identity_in_use: '這個 Google 帳號已被其他帳號綁定，無法重複綁定。',
  already_linked: '這個帳號已經綁定 Google，無需重複綁定。',
  account_disabled: '帳號目前無法使用，如有疑問請聯絡管理員。',
  flow_expired: '綁定流程已逾時或已失效，請回帳號設定頁重新發起。',
  flow_replaced: '偵測到較新的綁定流程，這個分頁的流程已失效，請重新發起。',
  provider_error: 'Google 服務暫時無法使用，請稍後再試。',
  unexpected_error: '綁定過程發生未預期的錯誤，請稍後再試。',
}
const GENERIC_LINK_ERROR = 'Google 綁定未完成，請再試一次。'

onMounted(() => {
  const params = new URLSearchParams(window.location.search)
  const result = params.get('result')
  const oauthError = params.get('oauthError')

  // 進頁先把 query 從網址列清掉（重新整理不會重複觸發同一則結果）
  window.history.replaceState({}, '', window.location.pathname)

  // 只有「result=linked 且完全沒有 oauthError」才算成功——後端正常不會同時帶兩者，
  // 但萬一 URL 被拼湊出矛盾參數，一律以失敗處理，不讓假造的 result=linked 蓋過錯誤。
  if (result === 'linked' && !oauthError) {
    notify('Google 綁定完成', '已成功綁定 Google 帳號。', 'success')
  } else if (oauthError) {
    notify('Google 綁定未完成', LINK_ERROR_MESSAGE[oauthError] ?? GENERIC_LINK_ERROR, 'error')
  } else {
    notify('Google 綁定未完成', GENERIC_LINK_ERROR, 'error')
  }

  router.replace('/account/security')
})
</script>

<template>
  <main class="auth-page">
    <section class="auth-card">
      <div class="spinner"></div>
      <p class="auth-card__subtitle" role="status" aria-live="polite">正在返回帳號設定…</p>
    </section>
  </main>
</template>
