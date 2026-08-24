<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { verifyEmail, resendVerificationEmail } from '@/api/auth.js'
import OperationMessage from '@/components/OperationMessage.vue'

/**
 * Email 驗證結果頁。對應 GET /api/accounts/verify-email?token=。
 *
 * 目前後端 app.mail.verification-base-url 直接指向這支後端 API 本身（回裸 JSON），
 * 不是這個前端頁面——要讓信件連結真的點得到這頁，後端那個屬性需要改成
 * http://localhost:5173/verify-email，屬於後端設定變更，不在本次前端範圍內，
 * 已另外回報。頁面本身仍先做好：帶 token 造訪即可運作，也可從連結失效時
 * 的重寄表單，或直接輸入網址走到這裡。
 */
const route = useRoute()

const status = ref('loading') // 'loading' | 'success' | 'error' | 'no-token'
const purpose = ref('')
const errorMessage = ref('')

const PURPOSE_TEXT = {
  REGISTRATION: '您的帳號已完成驗證，現在可以登入了。',
  REACTIVATION: '您的帳號已重新啟用，現在可以登入了。',
}

onMounted(async () => {
  const token = route.query.token
  if (!token || typeof token !== 'string') {
    status.value = 'no-token'
    return
  }
  try {
    const { data } = await verifyEmail(token)
    purpose.value = data.purpose
    status.value = 'success'
  } catch (e) {
    errorMessage.value = e.message
    status.value = 'error'
  }
})

// 重寄驗證信：連結失效／過期，或使用者沒有 token 直接進到這頁時共用同一個表單
const resendEmail = ref('')
const resending = ref(false)
const resendMessage = ref('')

async function onResend() {
  resendMessage.value = ''
  resending.value = true
  try {
    const { data } = await resendVerificationEmail(resendEmail.value.trim())
    resendMessage.value = data.message
  } catch (e) {
    resendMessage.value = e.message
  } finally {
    resending.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-card">
      <template v-if="status === 'loading'">
        <div class="spinner"></div>
        <p class="auth-card__subtitle">驗證中，請稍候…</p>
      </template>

      <template v-else-if="status === 'success'">
        <h1 class="auth-card__title">驗證成功</h1>
        <OperationMessage
          :message="PURPOSE_TEXT[purpose] || '驗證完成，現在可以登入了。'"
          tone="success"
        />
        <div class="auth-card__links">
          <RouterLink to="/login">前往登入</RouterLink>
        </div>
      </template>

      <template v-else>
        <h1 class="auth-card__title">{{ status === 'error' ? '驗證失敗' : 'Email 驗證' }}</h1>
        <OperationMessage
          v-if="status === 'error'"
          :message="errorMessage || '驗證連結無效或已過期。'"
          tone="error"
        />
        <p v-else class="auth-card__subtitle">請輸入註冊時使用的 Email，重新取得驗證信。</p>

        <form class="resend-form" @submit.prevent="onResend">
          <div class="form-group">
            <label class="form-label" for="resend-email">Email</label>
            <input
              id="resend-email"
              v-model.trim="resendEmail"
              class="form-input"
              type="email"
              required
              autocomplete="email"
              placeholder="you@example.com"
            />
          </div>

          <OperationMessage :message="resendMessage" tone="info" />

          <button class="btn btn-accent btn-block" type="submit" :disabled="resending">
            {{ resending ? '寄送中…' : '重新寄送驗證信' }}
          </button>
        </form>

        <div class="auth-card__links">
          <RouterLink to="/login">返回登入</RouterLink>
        </div>
      </template>
    </section>
  </main>
</template>

<style scoped>
.resend-form {
  margin-top: 1.2rem;
}
</style>
