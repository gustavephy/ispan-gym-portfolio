<script setup>
import { ref } from 'vue'
import { forgotPassword } from '@/api/auth.js'
import OperationMessage from '@/components/OperationMessage.vue'

/**
 * 忘記密碼申請頁。對應 POST /api/auth/forgot-password。
 * 後端一律回 202 + 固定訊息（反帳號列舉：不論 Email 是否存在、是否為 OAuth-only 帳號、
 * 是否被限流，回應完全相同），前端不做任何「帳號不存在」之類的額外判斷。
 */
const email = ref('')
const submitting = ref(false)
const resultMessage = ref('')
const errorMessage = ref('')

async function onSubmit() {
  resultMessage.value = ''
  errorMessage.value = ''
  submitting.value = true
  try {
    const { data } = await forgotPassword(email.value.trim())
    resultMessage.value = data.message
  } catch (e) {
    // 正常流程下這裡幾乎不會走到（後端一律回202），只有格式錯誤／網路失敗等才會進來
    errorMessage.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-card">
      <h1 class="auth-card__title">忘記密碼</h1>
      <p class="auth-card__subtitle">輸入您的 Email，我們會寄送密碼重設連結給您</p>

      <form @submit.prevent="onSubmit">
        <div class="form-group">
          <label class="form-label" for="forgot-email">Email</label>
          <input
            id="forgot-email"
            v-model.trim="email"
            class="form-input"
            type="email"
            required
            autocomplete="email"
            placeholder="you@example.com"
          />
        </div>

        <OperationMessage :message="resultMessage" tone="success" />
        <OperationMessage :message="errorMessage" tone="error" />

        <button class="btn btn-accent btn-block" type="submit" :disabled="submitting">
          {{ submitting ? '送出中…' : '寄送重設連結' }}
        </button>
      </form>

      <div class="auth-card__links">
        <RouterLink to="/login">返回登入</RouterLink>
      </div>
    </section>
  </main>
</template>
