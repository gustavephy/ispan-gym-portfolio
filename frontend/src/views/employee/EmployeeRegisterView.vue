<script setup>
import { computed, ref } from 'vue'
import { registerEmployee } from '@/api/auth.js'
import OperationMessage from '@/components/OperationMessage.vue'

/**
 * 員工申請頁。對應 POST /api/employees/register（EmployeeRegisterRequest）。
 * 員工沒有 Email 驗證信流程，送出後直接進入人工審核（PENDING_APPROVAL），
 * 不做會員註冊頁那套「寄驗證信、重寄、冷卻倒數」的畫面。
 * confirmPassword 只在前端比對，後端 DTO 沒有這個欄位，送出時不夾帶。
 */

const firstName = ref('')
const lastName = ref('')
const email = ref('')
const phone = ref('')
const password = ref('')
const confirmPassword = ref('')
const hireDate = ref('')

const errorMessage = ref('')
const submitting = ref(false)
const submittedEmail = ref('')

const hasMinLength = computed(() => password.value.length >= 8 && password.value.length <= 72)
const hasUpperCase = computed(() => /[A-Z]/.test(password.value))
const hasLowerCase = computed(() => /[a-z]/.test(password.value))
const hasDigit = computed(() => /\d/.test(password.value))
const hasNoWhitespace = computed(() => password.value.length === 0 || !/\s/.test(password.value))
// 後端 @ValidPassword 除了字元數 8~72，還要求 UTF-8 編碼後不得超過 72 bytes
// （BCrypt 以 byte 計算有效輸入長度，中文等多位元字元湊到 72 字元時可能已超過 72 bytes）
const hasValidByteLength = computed(() => new TextEncoder().encode(password.value).length <= 72)
const isPasswordValid = computed(
  () =>
    hasMinLength.value &&
    hasUpperCase.value &&
    hasLowerCase.value &&
    hasDigit.value &&
    hasNoWhitespace.value &&
    hasValidByteLength.value,
)
const isPasswordConfirmed = computed(
  () => confirmPassword.value.length > 0 && password.value === confirmPassword.value,
)
const isEmailValid = computed(() => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.value))

const isFormValid = computed(
  () =>
    firstName.value.trim() !== '' &&
    lastName.value.trim() !== '' &&
    isEmailValid.value &&
    phone.value.trim() !== '' &&
    isPasswordValid.value &&
    isPasswordConfirmed.value &&
    hireDate.value !== '',
)

async function onSubmit() {
  errorMessage.value = ''
  if (!isFormValid.value) {
    errorMessage.value = '請確認表單各欄位是否正確填寫'
    return
  }
  submitting.value = true
  try {
    const payload = {
      firstName: firstName.value.trim(),
      lastName: lastName.value.trim(),
      email: email.value.trim(),
      phone: phone.value.trim(),
      password: password.value,
      hireDate: hireDate.value,
    }
    const { data } = await registerEmployee(payload)
    submittedEmail.value = data.email
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
      <template v-if="submittedEmail">
        <h1 class="auth-card__title">申請已送出</h1>
        <OperationMessage
          :message="`我們已收到 ${submittedEmail} 的員工申請，請等待審核，申請核准後即可登入。`"
          tone="success"
        />
        <div class="auth-card__links">
          <RouterLink to="/login">前往登入</RouterLink>
        </div>
      </template>

      <template v-else>
        <h1 class="auth-card__title">員工申請</h1>
        <p class="auth-card__subtitle">填寫以下資料送出員工帳號申請，將由在職員工審核</p>

        <form @submit.prevent="onSubmit">
          <div class="form-group">
            <label class="form-label" for="emp-reg-email">Email</label>
            <input
              id="emp-reg-email"
              v-model.trim="email"
              class="form-input"
              type="email"
              maxlength="100"
              required
              autocomplete="email"
              placeholder="you@example.com"
            />
            <p v-if="email && !isEmailValid" class="form-error">✗ Email 格式不正確</p>
          </div>

          <div class="form-row">
            <div class="form-group">
              <label class="form-label" for="emp-reg-lastName">姓氏</label>
              <input
                id="emp-reg-lastName"
                v-model.trim="lastName"
                class="form-input"
                maxlength="50"
                required
              />
            </div>
            <div class="form-group">
              <label class="form-label" for="emp-reg-firstName">名字</label>
              <input
                id="emp-reg-firstName"
                v-model.trim="firstName"
                class="form-input"
                maxlength="50"
                required
              />
            </div>
          </div>

          <div class="form-group">
            <label class="form-label" for="emp-reg-phone">聯絡電話</label>
            <input
              id="emp-reg-phone"
              v-model.trim="phone"
              class="form-input"
              type="tel"
              maxlength="20"
              required
              autocomplete="tel"
            />
          </div>

          <div class="form-group">
            <label class="form-label" for="emp-reg-hireDate">到職日</label>
            <input
              id="emp-reg-hireDate"
              v-model="hireDate"
              class="form-input"
              type="date"
              required
            />
          </div>

          <div class="form-group">
            <label class="form-label" for="emp-reg-password">密碼</label>
            <input
              id="emp-reg-password"
              v-model="password"
              class="form-input"
              type="password"
              required
              autocomplete="new-password"
            />
            <ul v-if="password" class="pwd-rules">
              <li :class="{ 'pwd-rules__item--pass': hasMinLength }">8～72 個字元</li>
              <li :class="{ 'pwd-rules__item--pass': hasUpperCase }">包含大寫英文字母</li>
              <li :class="{ 'pwd-rules__item--pass': hasLowerCase }">包含小寫英文字母</li>
              <li :class="{ 'pwd-rules__item--pass': hasDigit }">包含數字</li>
              <li :class="{ 'pwd-rules__item--pass': hasNoWhitespace }">不可包含空白字元</li>
              <li :class="{ 'pwd-rules__item--pass': hasValidByteLength }">UTF-8 編碼不超過 72 bytes</li>
            </ul>
          </div>

          <div class="form-group">
            <label class="form-label" for="emp-reg-confirm">確認密碼</label>
            <input
              id="emp-reg-confirm"
              v-model="confirmPassword"
              class="form-input"
              type="password"
              required
              autocomplete="new-password"
            />
            <p v-if="confirmPassword && !isPasswordConfirmed" class="form-error">
              兩次密碼輸入不一致
            </p>
          </div>

          <OperationMessage :message="errorMessage" tone="error" />

          <button class="btn btn-accent btn-block" type="submit" :disabled="submitting">
            {{ submitting ? '送出中…' : '送出申請' }}
          </button>
        </form>

        <div class="auth-card__links">
          <span>申請曾被拒絕？<RouterLink to="/employee-reapply">重新申請</RouterLink></span>
          <span>已經有帳號？<RouterLink to="/login">前往登入</RouterLink></span>
          <span class="auth-card__links--secondary">暫時不繼續？<RouterLink to="/">返回首頁</RouterLink></span>
        </div>
      </template>
    </section>
  </main>
</template>

<style scoped>
.auth-card {
  max-width: 30rem;
}

.form-row {
  display: grid;
  /* 不能寫成裸的 1fr 1fr——欄位內容（<input>）想要的最小寬度比半張卡片還寬時，
     Grid 不會硬壓縮它，會讓整排一起撐出卡片外。minmax(0, 1fr) 明確允許欄位
     縮到比內容還窄，才會真的各佔一半。 */
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 0 1rem;
}

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

/* 窄螢幕（比照專案既有的 560px 斷點，見 MemberCourseView.vue）雙欄改單欄，
   避免姓氏/名字與密碼規則清單在手機上被擠壓到難以閱讀 */
@media (max-width: 560px) {
  .form-row,
  .pwd-rules {
    grid-template-columns: 1fr;
  }
}

/* 返回首頁連結刻意弱化，避免跟主要動線搶視覺焦點 */
.auth-card__links--secondary {
  margin-top: 0.4rem;
  font-size: 0.75rem;
  color: var(--text-muted);
}
</style>
