<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth.js'
import {
  completeGoogleRegistration,
  exchangeCode,
  googleLoginUrl,
  sanitizeReturnPath,
} from '@/api/oauth.js'
import OperationMessage from '@/components/OperationMessage.vue'

/**
 * 首次 Google 登入的補資料頁。對應後端 app.oauth.frontend-registration-url，
 * 後端在「Google 身分沒有既有綁定、且 email 尚未被任何帳號使用」時，把瀏覽器導到
 * /oauth/register?token=<registrationToken>。
 *
 * 為什麼需要這頁：Member 的 firstName／lastName／phone 是 NOT NULL，Google 不提供
 * 台灣手機號碼、姓名也不保證能可靠拆分，所以第一次一定要使用者自己補齊，不能塞假值。
 * email 與 Google 身分一律由後端 ticket 決定，這裡不傳、也拿不到（導回網址只帶 token）。
 *
 * 流程：填表 → POST /registration/complete 取得一次性 code → 立刻 POST /oauth/exchange
 * 換 JWT → 寫入既有 auth store → 依 returnPath 導頁。
 */
const router = useRouter()
const auth = useAuthStore()

// 註冊 ticket 只存在這個 ref，onMounted 即從網址清掉，不寫進 localStorage／sessionStorage。
const registrationToken = ref('')

/**
 * 終端狀態：一旦進入就隱藏整個表單、清掉 ticket、只留一顆行動按鈕，避免使用者
 * 用已消耗或狀態不明的 ticket 重送。null = 仍在填表；否則 { title, message, cta }。
 * cta：'google' = 重新用 Google 登入；'login' = 前往一般登入。
 */
const terminal = ref(null)

const lastName = ref('')
const firstName = ref('')
const phone = ref('')
const dob = ref('')
const gender = ref('')

const errorMessage = ref('') // 留在表單上的欄位層級錯誤（可修正）
const submitting = ref(false)

function enterTerminal(title, message, cta) {
  registrationToken.value = ''
  terminal.value = { title, message, cta }
}

onMounted(() => {
  const params = new URLSearchParams(window.location.search)
  const t = params.get('token')
  // 清掉網址列的 token（跟 callback 頁清 code 同一個理由：縮短一次性值留在瀏覽器歷史的時間）
  window.history.replaceState({}, '', window.location.pathname)
  if (!t) {
    enterTerminal('連結無效', '註冊連結無效或已失效，請重新使用 Google 登入。', 'google')
    return
  }
  registrationToken.value = t
})

// 本地日期字串。不使用 Date.parse／toISOString——'yyyy-MM-dd' 以 UTC 解析會讓「今天」
// 在某些時區被誤判成過去，跟後端 LocalDate 的 @Past（早於今天）對不上。
function localDateStr(d) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
const todayLocal = localDateStr(new Date())
const yesterdayLocal = (() => {
  const d = new Date()
  d.setDate(d.getDate() - 1)
  return localDateStr(d)
})()

const isPhoneValid = computed(() => /^09\d{8}$/.test(phone.value))
// <input type="date"> 的值本身就是 'yyyy-MM-dd'，直接字串比較即可
const isDobValid = computed(() => !dob.value || dob.value < todayLocal)
const isFormValid = computed(
  () =>
    lastName.value.trim() !== '' &&
    firstName.value.trim() !== '' &&
    isPhoneValid.value &&
    isDobValid.value,
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
      registrationToken: registrationToken.value,
      lastName: lastName.value.trim(),
      firstName: firstName.value.trim(),
      phone: phone.value.trim(),
      dob: dob.value || undefined,
      gender: gender.value || undefined,
    }
    const { data: completed } = await completeGoogleRegistration(payload)
    // 帳號已建立。接著換 JWT——這步失敗也不能回表單重送（ticket 已消耗），改走終端狀態。
    try {
      const { data: session } = await exchangeCode(completed.code)
      auth.setSession(session)
      router.replace(sanitizeReturnPath(session.returnPath) ?? '/')
    } catch {
      enterTerminal(
        '帳號已建立',
        '會員帳號已建立，但自動登入失敗。請重新使用 Google 登入即可，不會再要求補資料。',
        'google',
      )
    }
  } catch (e) {
    if (e.code === 'OAUTH_ACCOUNT_LINK_REQUIRED') {
      enterTerminal('這個 Email 已有帳號', '這個 Email 已有本站帳號，請使用原本的登入方式登入。', 'login')
    } else if (e.code === 'OAUTH_REGISTRATION_TICKET_INVALID') {
      enterTerminal('連結已失效', '這個註冊連結已失效，請重新使用 Google 登入。', 'google')
    } else if (e.code === 'INVALID_REQUEST') {
      // 欄位驗證錯誤：留在表單讓使用者修正
      errorMessage.value = e.message || '請確認表單各欄位是否正確填寫'
    } else {
      // 網路／逾時／500／未知：前端無法確定帳號是否已建立，不能安全重送原 ticket
      enterTerminal('註冊未完成', 'Google 註冊暫時無法完成，請重新使用 Google 登入。', 'google')
    }
  } finally {
    submitting.value = false
  }
}

function retryWithGoogle() {
  window.location.href = googleLoginUrl()
}

function goLogin() {
  router.push('/login')
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-card">
      <template v-if="terminal">
        <div role="alert">
          <h1 class="auth-card__title">{{ terminal.title }}</h1>
          <OperationMessage :message="terminal.message" tone="error" />
        </div>
        <button
          v-if="terminal.cta === 'google'"
          class="btn btn-accent btn-block"
          type="button"
          @click="retryWithGoogle"
        >
          重新使用 Google 登入
        </button>
        <button v-else class="btn btn-accent btn-block" type="button" @click="goLogin">
          前往登入
        </button>
        <div class="auth-card__links">
          <RouterLink to="/login">改用帳號密碼登入</RouterLink>
          <RouterLink to="/">返回首頁</RouterLink>
        </div>
      </template>

      <template v-else>
        <h1 class="auth-card__title">完成 Google 註冊</h1>
        <p class="auth-card__subtitle">
          這是您第一次用 Google 登入，請補上以下基本資料以建立會員帳號。
        </p>

        <form @submit.prevent="onSubmit">
          <div class="form-row">
            <div class="form-group">
              <label class="form-label" for="oauth-lastName">姓氏</label>
              <input
                id="oauth-lastName"
                v-model.trim="lastName"
                class="form-input"
                maxlength="50"
                autocomplete="family-name"
                required
              />
            </div>
            <div class="form-group">
              <label class="form-label" for="oauth-firstName">名字</label>
              <input
                id="oauth-firstName"
                v-model.trim="firstName"
                class="form-input"
                maxlength="50"
                autocomplete="given-name"
                required
              />
            </div>
          </div>

          <div class="form-group">
            <label class="form-label" for="oauth-phone">手機號碼</label>
            <input
              id="oauth-phone"
              v-model.trim="phone"
              class="form-input"
              type="tel"
              inputmode="numeric"
              autocomplete="tel"
              maxlength="10"
              required
              placeholder="0912345678"
            />
            <p v-if="phone && !isPhoneValid" class="form-error">手機格式須為 09 開頭共 10 碼</p>
          </div>

          <div class="form-row">
            <div class="form-group">
              <label class="form-label" for="oauth-dob">生日（選填）</label>
              <input
                id="oauth-dob"
                v-model="dob"
                class="form-input"
                type="date"
                :max="yesterdayLocal"
                autocomplete="bday"
              />
              <p v-if="dob && !isDobValid" class="form-error">生日必須是過去的日期</p>
            </div>
            <div class="form-group">
              <label class="form-label" for="oauth-gender">性別（選填）</label>
              <select id="oauth-gender" v-model="gender" class="form-input">
                <option value="">不指定</option>
                <option value="M">男性</option>
                <option value="F">女性</option>
                <option value="OTHER">其他</option>
              </select>
            </div>
          </div>

          <p class="oauth-consent">
            使用 Google 登入時，我們會讀取您 Google 帳號的 Email、姓名與大頭貼，用於建立這個系統的會員帳號。
            Google 僅作為一種登入方式，您的身分與權限仍由本系統帳號管理。完成後可於「帳號設定」查看 Google 綁定狀態。
          </p>

          <OperationMessage :message="errorMessage" tone="error" />

          <button
            class="btn btn-accent btn-block"
            type="submit"
            :disabled="submitting || !isFormValid"
          >
            {{ submitting ? '建立中…' : '建立會員帳號' }}
          </button>
        </form>

        <div class="auth-card__links">
          <span>不是要用 Google？<RouterLink to="/login">返回登入</RouterLink></span>
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

.oauth-consent {
  margin: 0.2rem 0 1.2rem;
  padding: 0.7rem 0.9rem;
  background: var(--bg-soft);
  border: 1px dashed var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.78rem;
  line-height: 1.7;
  color: var(--text-muted);
}

.auth-card__links--secondary {
  margin-top: 0.4rem;
  font-size: 0.75rem;
  color: var(--text-muted);
}

@media (max-width: 560px) {
  .form-row {
    grid-template-columns: 1fr;
  }
}
</style>
