<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { forgotPassword } from '@/api/auth.js'
import OperationMessage from '@/components/OperationMessage.vue'

/**
 * 忘記密碼申請頁。對應 POST /api/auth/forgot-password。
 * 後端一律回 202 + 固定訊息（反帳號列舉：不論 Email 是否存在、是否為 OAuth-only 帳號、
 * 是否被限流，回應完全相同），前端不做任何「帳號不存在」之類的額外判斷。
 */

/**
 * 送出後的冷卻秒數，與後端 app.mail.password-reset-resend-cooldown-seconds=60
 * 保持一致——冷卻期內再按，後端一樣回 202 固定訊息但其實沒有真的再寄一封，
 * 讓按鈕在這段時間內不能點，避免使用者誤以為每次按都寄出新的信。
 */
const COOLDOWN_SECONDS = 60

const email = ref('')
const submitting = ref(false)
const resultMessage = ref('')
const errorMessage = ref('')

const cooldown = ref(0)
let cooldownTimer = null

// 送出成功後記住申請的信箱；使用者可能在信件連結那頭（另一個分頁）完成重設，
// 監聽同一個 BroadcastChannel，Email 正規化後跟這頁申請的信箱相符才切換畫面，
// 避免把別人帳號的重設結果套進來。比照 MemberRegisterView 的 verifiedElsewhere。
const submittedEmail = ref('')
const resetCompleted = ref(false)
let resetChannel = null

function normalizeEmail(value) {
  return (value || '').trim().toLowerCase()
}

onMounted(() => {
  if (typeof BroadcastChannel === 'undefined') return
  resetChannel = new BroadcastChannel('password-reset')
  resetChannel.onmessage = (event) => {
    const doneEmail = event.data?.email
    if (!doneEmail || !submittedEmail.value) return
    if (normalizeEmail(doneEmail) !== normalizeEmail(submittedEmail.value)) return
    resetCompleted.value = true
    clearInterval(cooldownTimer)
    cooldown.value = 0
  }
})

onUnmounted(() => resetChannel?.close())

function startCooldown() {
  cooldown.value = COOLDOWN_SECONDS
  clearInterval(cooldownTimer)
  cooldownTimer = setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0) {
      clearInterval(cooldownTimer)
    }
  }, 1000)
}

onUnmounted(() => clearInterval(cooldownTimer))

const cooldownLabel = computed(() => {
  const m = Math.floor(cooldown.value / 60)
  const s = cooldown.value % 60
  return `${m}:${String(s).padStart(2, '0')}`
})

async function onSubmit() {
  resultMessage.value = ''
  errorMessage.value = ''
  submitting.value = true
  try {
    const { data } = await forgotPassword(email.value.trim())
    resultMessage.value = data.message
    submittedEmail.value = email.value.trim()
    startCooldown()
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
      <template v-if="resetCompleted">
        <h1 class="auth-card__title">密碼已重設</h1>
        <OperationMessage message="您的密碼已在另一個分頁完成重設，請使用新密碼重新登入。" tone="success" />
        <div class="auth-card__links">
          <RouterLink to="/login">前往登入</RouterLink>
        </div>
      </template>

      <template v-else>
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

        <button class="btn btn-accent btn-block" type="submit" :disabled="submitting || cooldown > 0">
          {{
            submitting
              ? '送出中…'
              : cooldown > 0
                ? `寄送重設連結（${cooldownLabel}）`
                : '寄送重設連結'
          }}
        </button>
      </form>

      <div class="auth-card__links">
        <RouterLink to="/login">返回登入</RouterLink>
      </div>
      </template>
    </section>
  </main>
</template>
