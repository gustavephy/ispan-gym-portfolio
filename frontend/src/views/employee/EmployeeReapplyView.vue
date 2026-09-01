<script setup>
import { computed, ref } from 'vue'
import { reapplyEmployee } from '@/api/auth.js'
import OperationMessage from '@/components/OperationMessage.vue'
import {
  TW_MOBILE_MAXLENGTH,
  TW_MOBILE_PLACEHOLDER,
  TW_MOBILE_ERROR,
  isValidTwMobile,
} from '@/utils/phone.js'

/**
 * 員工重新申請頁。對應 POST /api/employees/reapply（EmployeeReapplyRequest）。
 * 僅 REJECTED 的申請者可用；password 只用來證明是本人、不會被變更，
 * 因此不做密碼強度提示、不要求確認密碼——沿用舊密碼即可送出。
 */

const email = ref('')
const password = ref('')
const firstName = ref('')
const lastName = ref('')
const phone = ref('')
const hireDate = ref('')

const errorMessage = ref('')
const submitting = ref(false)
const submittedEmail = ref('')

const isEmailValid = computed(() => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.value))
const isPhoneValid = computed(() => isValidTwMobile(phone.value))

const isFormValid = computed(
  () =>
    isEmailValid.value &&
    password.value.trim() !== '' &&
    firstName.value.trim() !== '' &&
    lastName.value.trim() !== '' &&
    isPhoneValid.value &&
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
      email: email.value.trim(),
      password: password.value,
      firstName: firstName.value.trim(),
      lastName: lastName.value.trim(),
      phone: phone.value.trim(),
      hireDate: hireDate.value,
    }
    const { data } = await reapplyEmployee(payload)
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
        <h1 class="auth-card__title">已重新送出申請</h1>
        <OperationMessage
          :message="`${submittedEmail} 的員工申請已重新送出，請等待審核，申請核准後即可登入。`"
          tone="success"
        />
        <div class="auth-card__links">
          <RouterLink to="/login">前往登入</RouterLink>
        </div>
      </template>

      <template v-else>
        <h1 class="auth-card__title">重新申請</h1>
        <p class="auth-card__subtitle">
          若您先前的員工申請未通過審核，請在此以原 Email 與密碼重新送出申請
        </p>

        <form @submit.prevent="onSubmit">
          <div class="form-group">
            <label class="form-label" for="emp-reapply-email">Email</label>
            <input
              id="emp-reapply-email"
              v-model.trim="email"
              class="form-input"
              type="email"
              required
              autocomplete="email"
              placeholder="you@example.com"
            />
            <p v-if="email && !isEmailValid" class="form-error">✗ Email 格式不正確</p>
          </div>

          <div class="form-group">
            <label class="form-label" for="emp-reapply-password">密碼</label>
            <input
              id="emp-reapply-password"
              v-model="password"
              class="form-input"
              type="password"
              required
              autocomplete="current-password"
            />
            <p class="form-hint">用來驗證是您本人提出申請，不會變更密碼</p>
          </div>

          <div class="form-row">
            <div class="form-group">
              <label class="form-label" for="emp-reapply-lastName">姓氏</label>
              <input
                id="emp-reapply-lastName"
                v-model.trim="lastName"
                class="form-input"
                maxlength="50"
                required
              />
            </div>
            <div class="form-group">
              <label class="form-label" for="emp-reapply-firstName">名字</label>
              <input
                id="emp-reapply-firstName"
                v-model.trim="firstName"
                class="form-input"
                maxlength="50"
                required
              />
            </div>
          </div>

          <div class="form-group">
            <label class="form-label" for="emp-reapply-phone">聯絡電話</label>
            <input
              id="emp-reapply-phone"
              v-model.trim="phone"
              class="form-input"
              type="tel"
              :maxlength="TW_MOBILE_MAXLENGTH"
              :placeholder="TW_MOBILE_PLACEHOLDER"
              required
              autocomplete="tel"
            />
            <p v-if="phone && !isPhoneValid" class="form-error">✗ {{ TW_MOBILE_ERROR }}</p>
          </div>

          <div class="form-group">
            <label class="form-label" for="emp-reapply-hireDate">到職日</label>
            <input
              id="emp-reapply-hireDate"
              v-model="hireDate"
              class="form-input"
              type="date"
              required
            />
          </div>

          <OperationMessage :message="errorMessage" tone="error" />

          <button class="btn btn-accent btn-block" type="submit" :disabled="submitting">
            {{ submitting ? '送出中…' : '重新送出申請' }}
          </button>
        </form>

        <div class="auth-card__links">
          <span>還沒申請過？<RouterLink to="/employee-register">前往員工申請</RouterLink></span>
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
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 0 1rem;
}

/* 窄螢幕（比照專案既有的 560px 斷點，見 MemberCourseView.vue）雙欄改單欄 */
@media (max-width: 560px) {
  .form-row {
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
