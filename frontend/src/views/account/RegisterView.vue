<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { checkEmailAvailability, registerMember, resendVerificationEmail } from '@/api/auth.js'
import OperationMessage from '@/components/OperationMessage.vue'

/**
 * 會員註冊頁。對應 POST /api/members/register（MemberRegisterRequest）。
 * confirmPassword 只在前端比對，後端 DTO 沒有這個欄位，送出時不夾帶。
 * 密碼規則（8-72字元、需含大小寫英文與數字、不可有空白）與後端 @ValidPassword 一致，
 * 這裡的即時檢查只是提前給回饋，真正的驗證仍在後端。
 */

/**
 * 重寄驗證信冷卻秒數，與後端 app.mail.verification-resend-cooldown-minutes=1
 * 保持一致——倒數結束才讓按鈕可點，避免使用者在後端冷卻期內點了卻因為
 * 反列舉設計（一律回同一句固定訊息）而誤以為寄出了、實際上後端沒真的寄。
 */
const RESEND_COOLDOWN_SECONDS = 60
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

const resendCooldown = ref(0)
const resending = ref(false)
const resendMessage = ref('')
let resendTimer = null

function startResendCooldown() {
  resendCooldown.value = RESEND_COOLDOWN_SECONDS
  clearInterval(resendTimer)
  resendTimer = setInterval(() => {
    resendCooldown.value -= 1
    if (resendCooldown.value <= 0) {
      clearInterval(resendTimer)
    }
  }, 1000)
}

onUnmounted(() => clearInterval(resendTimer))

const resendCooldownLabel = computed(() => {
  const m = Math.floor(resendCooldown.value / 60)
  const s = resendCooldown.value % 60
  return `${m}:${String(s).padStart(2, '0')}`
})

async function onResend() {
  resendMessage.value = ''
  resending.value = true
  try {
    const { data } = await resendVerificationEmail(registeredEmail.value)
    resendMessage.value = data.message
  } catch (e) {
    resendMessage.value = e.message
  } finally {
    resending.value = false
    startResendCooldown()
  }
}

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

/**
 * Email 即時可用性檢查——刻意接受這是一個帳號列舉管道（後端有限流降低風險），
 * 用來換取「填完 Email 就先知道能不能用」的體驗。
 * 只在欄位失焦（blur）且格式已經正確時才查，不是每次打字都查。
 * emailCheckState： 'idle' | 'checking' | 'available' | 'taken' | 'error' | 'rate-limited'
 * 這支只是 UX 提示，真正的唯一性判斷仍在 POST /api/members/register。
 */
const emailCheckState = ref('idle')
let emailCheckRequestId = 0

// 使用者重新修改 Email：立即清掉舊的檢查結果，並讓任何還在飛行中的舊請求失效——
// 不遞增 emailCheckRequestId 的話，改完 Email 但還沒再次失焦前，前一個 Email 的
// 回應仍會通過 onEmailBlur() 的 requestId 比對，把結果誤蓋到現在這個新 Email 上。
watch(email, () => {
  emailCheckState.value = 'idle'
  emailCheckRequestId++
})

async function onEmailBlur() {
  if (!isEmailValid.value) return

  const requestId = ++emailCheckRequestId
  emailCheckState.value = 'checking'
  try {
    const { data } = await checkEmailAvailability(email.value.trim())
    if (requestId !== emailCheckRequestId) return // 舊回應晚到，不能覆蓋掉更新的結果
    emailCheckState.value = data.available ? 'available' : 'taken'
  } catch (e) {
    if (requestId !== emailCheckRequestId) return
    emailCheckState.value = e.code === 'EMAIL_CHECK_RATE_LIMITED' ? 'rate-limited' : 'error'
  }
}

// 送出前確認彈窗：驗證信實際會寄到哪個地址，讓使用者有機會抓出「格式正確但打錯」的手滑
const showConfirmModal = ref(false)

function onSubmitClick() {
  errorMessage.value = ''
  if (!isFormValid.value) {
    errorMessage.value = '請確認表單各欄位是否正確填寫'
    return
  }
  showConfirmModal.value = true
}

async function confirmAndRegister() {
  showConfirmModal.value = false
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
    startResendCooldown()
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

        <OperationMessage :message="resendMessage" tone="info" />
        <button
          class="btn btn-accent btn-block resend-btn"
          type="button"
          :disabled="resendCooldown > 0 || resending"
          @click="onResend"
        >
          {{
            resending
              ? '寄送中…'
              : resendCooldown > 0
                ? `重新寄送驗證信（${resendCooldownLabel}）`
                : '重新寄送驗證信'
          }}
        </button>

        <div class="auth-card__links">
          <RouterLink to="/login">前往登入</RouterLink>
        </div>
      </template>

      <template v-else>
        <h1 class="auth-card__title">會員註冊</h1>
        <p class="auth-card__subtitle">建立帳號，開始使用威力健身房管理系統</p>

        <form @submit.prevent="onSubmitClick">
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
              @blur="onEmailBlur"
            />
            <p v-if="email && !isEmailValid" class="form-error">✗ Email 格式不正確</p>
            <p v-else-if="emailCheckState === 'checking'" class="email-check email-check--neutral">檢查中…</p>
            <p v-else-if="emailCheckState === 'available'" class="email-check email-check--success">
              ✓ 此 Email 可以使用
            </p>
            <p v-else-if="emailCheckState === 'taken'" class="email-check email-check--error">
              ✗ 此 Email 已註冊，請直接<RouterLink to="/login">登入</RouterLink>
            </p>
            <p v-else-if="emailCheckState === 'error'" class="email-check email-check--neutral">
              暫時無法檢查，仍可送出註冊表單
            </p>
            <p v-else-if="emailCheckState === 'rate-limited'" class="email-check email-check--neutral">
              檢查次數過多，請稍後再試
            </p>
          </div>

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

    <div v-if="showConfirmModal" class="modal-overlay" @click.self="showConfirmModal = false">
      <div class="modal-content">
        <h3 class="modal-title">確認 Email 地址</h3>
        <p>驗證信將寄送至 <strong>{{ email }}</strong>，請確認地址正確。</p>
        <div class="modal-actions">
          <button class="btn btn-accent" type="button" :disabled="submitting" @click="confirmAndRegister">
            {{ submitting ? '送出中…' : '確認送出' }}
          </button>
          <button class="btn btn-outline" type="button" :disabled="submitting" @click="showConfirmModal = false">
            返回修改
          </button>
        </div>
      </div>
    </div>
  </main>
</template>

<style scoped>
.auth-card {
  max-width: 30rem;
}

.resend-btn {
  margin: 0.4rem 0 1.4rem;
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

.email-check {
  margin-top: 0.3rem;
  font-size: 0.8rem;
}

.email-check--success {
  color: #3f7a3a;
}

.email-check--error {
  color: #b3452f;
}

.email-check--error a {
  color: inherit;
  text-decoration: underline;
}

.email-check--neutral {
  color: var(--text-muted);
}

/* 送出前確認彈窗，比照 AdminGroupCourseView.vue 等既有頁面同一套 modal 規格 */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  z-index: 100;
}

.modal-content {
  width: 100%;
  max-width: 420px;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-hover);
  padding: 1.75rem;
}

.modal-title {
  margin: 0 0 1.25rem;
  font-size: 1.15rem;
  color: var(--text-primary);
}

.modal-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1.5rem;
}
</style>
