<script setup>
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { resetPassword } from '@/api/auth.js'
import OperationMessage from '@/components/OperationMessage.vue'
import PasswordInput from '@/components/PasswordInput.vue'

/**
 * 憑 Token 重設密碼頁。對應 POST /api/auth/reset-password，是後端
 * app.mail.password-reset-base-url 寫死導向的路徑，不可隨意更動。
 * 成功不回 JWT（見規格：重設密碼視為救援可能已外流的帳號，一律導回登入頁重新輸入密碼）。
 */
const route = useRoute()
const token = computed(() => (typeof route.query.token === 'string' ? route.query.token : ''))

// 重設常常發生在另一個分頁（使用者從信箱點連結開啟），廣播出去讓還開著的「忘記密碼
// 已寄出」分頁（ForgotPasswordView）即時知道已完成、切成「密碼已重設」並停用重寄。
// 比照 EmailVerificationView 的 broadcastVerified。
function broadcastReset(email) {
  if (typeof BroadcastChannel === 'undefined' || !email) return
  const channel = new BroadcastChannel('password-reset')
  channel.postMessage({ email })
  channel.close()
}

const newPassword = ref('')
const confirmPassword = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const done = ref(false)

const hasMinLength = computed(() => newPassword.value.length >= 8 && newPassword.value.length <= 72)
const hasUpperCase = computed(() => /[A-Z]/.test(newPassword.value))
const hasLowerCase = computed(() => /[a-z]/.test(newPassword.value))
const hasDigit = computed(() => /\d/.test(newPassword.value))
const hasNoWhitespace = computed(
  () => newPassword.value.length === 0 || !/\s/.test(newPassword.value),
)
const isPasswordValid = computed(
  () =>
    hasMinLength.value &&
    hasUpperCase.value &&
    hasLowerCase.value &&
    hasDigit.value &&
    hasNoWhitespace.value,
)
const isPasswordConfirmed = computed(
  () => confirmPassword.value.length > 0 && newPassword.value === confirmPassword.value,
)
const isFormValid = computed(() => isPasswordValid.value && isPasswordConfirmed.value)

async function onSubmit() {
  errorMessage.value = ''
  if (!isFormValid.value) {
    errorMessage.value = '請確認新密碼是否符合規則且兩次輸入一致'
    return
  }
  submitting.value = true
  try {
    const { data } = await resetPassword({
      token: token.value,
      newPassword: newPassword.value,
      confirmPassword: confirmPassword.value,
    })
    done.value = true
    broadcastReset(data?.email)
  } catch (e) {
    errorMessage.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-card">
      <template v-if="!token">
        <h1 class="auth-card__title">連結無效</h1>
        <OperationMessage message="這個重設密碼連結不完整，請重新申請忘記密碼。" tone="error" />
        <div class="auth-card__links">
          <RouterLink to="/forgot-password">重新申請忘記密碼</RouterLink>
        </div>
      </template>

      <template v-else-if="done">
        <h1 class="auth-card__title">密碼已重設</h1>
        <OperationMessage message="密碼已成功重設，請使用新密碼重新登入。" tone="success" />
        <div class="auth-card__links">
          <RouterLink to="/login">前往登入</RouterLink>
        </div>
      </template>

      <template v-else>
        <h1 class="auth-card__title">重設密碼</h1>
        <p class="auth-card__subtitle">請輸入新密碼</p>

        <form @submit.prevent="onSubmit">
          <div class="form-group">
            <label class="form-label" for="reset-password">新密碼</label>
            <PasswordInput
              id="reset-password"
              v-model="newPassword"
              required
              autocomplete="new-password"
            />
            <ul v-if="newPassword" class="pwd-rules">
              <li :class="{ 'pwd-rules__item--pass': hasMinLength }">8～72 個字元</li>
              <li :class="{ 'pwd-rules__item--pass': hasUpperCase }">包含大寫英文字母</li>
              <li :class="{ 'pwd-rules__item--pass': hasLowerCase }">包含小寫英文字母</li>
              <li :class="{ 'pwd-rules__item--pass': hasDigit }">包含數字</li>
              <li :class="{ 'pwd-rules__item--pass': hasNoWhitespace }">不可包含空白字元</li>
            </ul>
          </div>

          <div class="form-group">
            <label class="form-label" for="reset-confirm">確認新密碼</label>
            <PasswordInput
              id="reset-confirm"
              v-model="confirmPassword"
              required
              autocomplete="new-password"
            />
            <p v-if="confirmPassword && !isPasswordConfirmed" class="form-error">
              兩次密碼輸入不一致
            </p>
          </div>

          <OperationMessage :message="errorMessage" tone="error" />

          <button class="btn btn-accent btn-block" type="submit" :disabled="submitting">
            {{ submitting ? '重設中…' : '重設密碼' }}
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
.pwd-rules {
  list-style: none;
  margin: 0.5rem 0 0;
  padding: 0.6rem 0.8rem;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.3rem 0.6rem;
  background: var(--bg-soft);
  border: 1px dashed var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.78rem;
  color: var(--text-muted);
}

.pwd-rules li::before {
  content: '✗ ';
}

.pwd-rules__item--pass {
  color: var(--accent);
  font-weight: 600;
}

/* 加上 li，特異度才追得上上面那條 .pwd-rules li::before，符號才會真的換成 ✓ */
.pwd-rules li.pwd-rules__item--pass::before {
  content: '✓ ';
}
</style>
