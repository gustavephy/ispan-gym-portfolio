<script setup>
import { computed, ref } from 'vue'
import { registerMember } from '@/api/auth.js'
import OperationMessage from '@/components/OperationMessage.vue'

/**
 * 會員註冊頁。對應 POST /api/members/register（MemberRegisterRequest）。
 * confirmPassword 只在前端比對，後端 DTO 沒有這個欄位，送出時不夾帶。
 * 密碼規則（8-72字元、需含大小寫英文與數字、不可有空白）與後端 @ValidPassword 一致，
 * 這裡的即時檢查只是提前給回饋，真正的驗證仍在後端。
 */
const firstName = ref('')
const lastName = ref('')
const email = ref('')
const phone = ref('')
const password = ref('')
const confirmPassword = ref('')
const dob = ref('')
const gender = ref('')

const errorMessage = ref('')
const submitting = ref(false)
const registeredEmail = ref('')

const hasMinLength = computed(() => password.value.length >= 8 && password.value.length <= 72)
const hasUpperCase = computed(() => /[A-Z]/.test(password.value))
const hasLowerCase = computed(() => /[a-z]/.test(password.value))
const hasDigit = computed(() => /\d/.test(password.value))
const hasNoWhitespace = computed(() => password.value.length === 0 || !/\s/.test(password.value))
const isPasswordValid = computed(
  () =>
    hasMinLength.value &&
    hasUpperCase.value &&
    hasLowerCase.value &&
    hasDigit.value &&
    hasNoWhitespace.value,
)
const isPasswordConfirmed = computed(
  () => confirmPassword.value.length > 0 && password.value === confirmPassword.value,
)
const isPhoneValid = computed(() => /^09\d{8}$/.test(phone.value))
const isEmailValid = computed(() => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.value))

const isFormValid = computed(
  () =>
    firstName.value.trim() !== '' &&
    lastName.value.trim() !== '' &&
    isEmailValid.value &&
    isPhoneValid.value &&
    isPasswordValid.value &&
    isPasswordConfirmed.value,
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
      dob: dob.value || undefined,
      gender: gender.value || undefined,
    }
    const { data } = await registerMember(payload)
    registeredEmail.value = data.email
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
      <template v-if="registeredEmail">
        <h1 class="auth-card__title">註冊成功</h1>
        <OperationMessage
          :message="`我們已寄出驗證信到 ${registeredEmail}，請查收並完成驗證後即可登入。`"
          tone="success"
        />
        <div class="auth-card__links">
          <RouterLink to="/login">前往登入</RouterLink>
        </div>
      </template>

      <template v-else>
        <h1 class="auth-card__title">會員註冊</h1>
        <p class="auth-card__subtitle">建立帳號，開始使用威力健身房管理系統</p>

        <form @submit.prevent="onSubmit">
          <div class="form-row">
            <div class="form-group">
              <label class="form-label" for="reg-lastName">姓氏</label>
              <input
                id="reg-lastName"
                v-model.trim="lastName"
                class="form-input"
                maxlength="50"
                required
              />
            </div>
            <div class="form-group">
              <label class="form-label" for="reg-firstName">名字</label>
              <input
                id="reg-firstName"
                v-model.trim="firstName"
                class="form-input"
                maxlength="50"
                required
              />
            </div>
          </div>

          <div class="form-group">
            <label class="form-label" for="reg-email">Email</label>
            <input
              id="reg-email"
              v-model.trim="email"
              class="form-input"
              type="email"
              maxlength="100"
              required
              autocomplete="email"
              placeholder="you@example.com"
            />
            <p v-if="email && !isEmailValid" class="form-error">Email 格式不正確</p>
          </div>

          <div class="form-group">
            <label class="form-label" for="reg-phone">手機號碼</label>
            <input
              id="reg-phone"
              v-model.trim="phone"
              class="form-input"
              maxlength="10"
              required
              placeholder="0912345678"
            />
            <p v-if="phone && !isPhoneValid" class="form-error">手機格式須為 09 開頭共 10 碼</p>
          </div>

          <div class="form-row">
            <div class="form-group">
              <label class="form-label" for="reg-dob">生日（選填）</label>
              <input id="reg-dob" v-model="dob" class="form-input" type="date" />
            </div>
            <div class="form-group">
              <label class="form-label" for="reg-gender">性別（選填）</label>
              <select id="reg-gender" v-model="gender" class="form-input">
                <option value="">不指定</option>
                <option value="M">男性</option>
                <option value="F">女性</option>
                <option value="OTHER">其他</option>
              </select>
            </div>
          </div>

          <div class="form-group">
            <label class="form-label" for="reg-password">密碼</label>
            <input
              id="reg-password"
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
            </ul>
          </div>

          <div class="form-group">
            <label class="form-label" for="reg-confirm">確認密碼</label>
            <input
              id="reg-confirm"
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
            {{ submitting ? '註冊中…' : '註冊' }}
          </button>
        </form>

        <div class="auth-card__links">
          <span>已經有帳號？<RouterLink to="/login">前往登入</RouterLink></span>
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

.pwd-rules__item--pass::before {
  content: '✓ ';
}
</style>
