<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '@/api/auth.js'
import { googleLoginUrl } from '@/api/oauth.js'
import { useAuthStore } from '@/stores/auth.js'
import OperationMessage from '@/components/OperationMessage.vue'

/**
 * 登入頁。同時也是「就地顯示錯誤」的示範：
 * /api/auth/login 屬於匿名端點（見 api/auth.js 的 ANONYMOUS_CONFIG），
 * 攔截器不會為它跳全域錯誤或導向登入頁，帳密錯誤等結果由這裡自己顯示在表單上。
 *
 * 也承接 Google 登入失敗：後端 app.oauth.frontend-error-url 指向 /login，失敗時
 * 會導回 /login?oauthError=<reason>（OAuthRedirectReason 的小寫），這裡把 reason
 * 對應成使用者看得懂的訊息顯示。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const email = ref('')
const password = ref('')
const errorMessage = ref('')
const submitting = ref(false)

// Google 登入按鈕預設顯示（OAuth 後端已在 dev、GOOGLE_OAUTH_ENABLED=true）。
// 要在某個環境關掉，設 VITE_GOOGLE_OAUTH_ENABLED=false（見 frontend/.env.example）。
// 根目錄 .gitignore 忽略所有 .env，所以用「預設開、明確設 false 才關」，不靠 committed 的 .env。
const googleOAuthEnabled = import.meta.env.VITE_GOOGLE_OAUTH_ENABLED !== 'false'

// 後端只把固定白名單的 reason（小寫）放進網址，不帶任何敏感資訊，前端只依它決定文案。
const OAUTH_ERROR_MESSAGES = {
  access_denied: '您已取消 Google 登入。',
  flow_expired: '登入流程已逾時，請重新使用 Google 登入。',
  flow_replaced: '這個登入流程已被較新的操作取代，請重新使用 Google 登入。',
  account_link_required: '這個 Google 帳號的 Email 已有本站帳號，請使用原本的登入方式登入。',
  email_not_verified: '您的 Google 帳號 Email 尚未驗證，無法用於登入。',
  account_disabled: '此帳號已被停用，如有疑問請聯絡管理員。',
  provider_error: 'Google 登入服務暫時發生問題，請稍後再試。',
  unexpected_error: '登入時發生非預期的錯誤，請稍後再試。',
}

onMounted(() => {
  const reason = route.query.oauthError
  if (typeof reason === 'string' && reason) {
    errorMessage.value = OAUTH_ERROR_MESSAGES[reason] || 'Google 登入未完成，請再試一次。'
    // 清掉網址列的 oauthError，重新整理不會再出現同一則訊息
    router.replace({ path: '/login', query: route.query.redirect ? { redirect: route.query.redirect } : {} })
  }
})

function loginWithGoogle() {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : undefined
  window.location.href = googleLoginUrl(redirect)
}

/**
 * 開發／展示用測試帳號，點擊自動填入表單。
 * 只在 dev build 顯示（import.meta.env.DEV），正式打包自動隱藏。
 * password 為 null 表示尚未登錄已知密碼，點擊只填 email。
 */
const showTestAccounts = import.meta.env.DEV
// 測試帳號密碼統一為 Admin@12345（2026-08-17 以種子管理員的 bcrypt hash 覆寫本機資料庫）
const TEST_ACCOUNTS = [
  { label: '會員', email: 'wendy-test@local.test', password: 'Admin@12345' },
  { label: '教練', email: 'emp-trainer@local.test', password: 'Admin@12345' },
  // 純管理員（無會員身分），登入後只會看到管理工作區；
  // emp-admin 另外帶會員身分，要展示角色疊加時才用
  { label: '管理員', email: 'admin-only@local.test', password: 'Admin@12345' },
  // label 裡的換行字元靠 .test-accounts__role 的 white-space: pre-line 生效，
  // 讓「(種子)」固定斷成自己一行，不受欄寬影響自然換行斷在奇怪的位置。
  // 括號刻意用半形：全形括號「（」自帶左側留白，換行後變成該行開頭字元時
  // 那段留白會被看成多一個空格，換成半形括號沒有這個問題。
  { label: '管理員\n(種子)', email: 'admin@wpgym.local', password: 'Admin@12345' },
]

/**
 * 測試帳號面板的 email 太長、欄寬不夠時需要換行，但瀏覽器預設會把「-」當成
 * 斷行機會點，斷出「wendy-」／「test@local.test」這種不好看的位置。
 * 用兩個零寬字元取代：
 *   - 連字號後插入「word joiner」（U+2060）：等同宣告這裡「禁止斷行」，
 *     蓋掉瀏覽器對連字號的預設斷行機會。
 *   - @ 前插入「zero-width space」（U+200B）：唯一保留的斷行機會點。
 * 兩者都是零寬度字元，不影響實際顯示內容，只影響換行規則。
 */
const WORD_JOINER = '⁠' // 禁止斷行控制字元，蓋掉連字號後面的預設斷行機會
const ZERO_WIDTH_SPACE = '​' // 唯一保留的斷行機會點

function wrapEmailAtAt(email) {
  return email.replace(/-/g, `-${WORD_JOINER}`).replace('@', `${ZERO_WIDTH_SPACE}@`)
}

function fillAccount(account) {
  email.value = account.email
  password.value = account.password ?? ''
  errorMessage.value = ''
}

async function onSubmit() {
  errorMessage.value = ''
  submitting.value = true
  try {
    const { data } = await login({ email: email.value, password: password.value })
    auth.setSession(data)
    // 登入前被攔截的頁面；沒有就回首頁
    router.replace(route.query.redirect || '/')
  } catch (e) {
    // 攔截器已正規化，直接取 message（後端會回「帳號或密碼錯誤」「員工帳號尚待審核」
    // 「登入嘗試次數過多，請稍後再試」等具體說明）
    errorMessage.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-card">
      <h1 class="auth-card__title">登入</h1>
      <p class="auth-card__subtitle">歡迎回來，請輸入帳號密碼</p>

      <form @submit.prevent="onSubmit">
        <div class="form-group">
          <label class="form-label" for="login-email">Email</label>
          <input
            id="login-email"
            v-model.trim="email"
            class="form-input"
            type="email"
            required
            autocomplete="email"
            placeholder="you@example.com"
          />
        </div>

        <div class="form-group">
          <label class="form-label" for="login-password">密碼</label>
          <input
            id="login-password"
            v-model="password"
            class="form-input"
            type="password"
            required
            autocomplete="current-password"
          />
        </div>

        <OperationMessage :message="errorMessage" tone="error" />

        <button class="btn btn-accent btn-block" type="submit" :disabled="submitting">
          {{ submitting ? '登入中…' : '登入' }}
        </button>
      </form>

      <template v-if="googleOAuthEnabled">
        <div class="oauth-divider"><span>或</span></div>

        <button class="btn btn-outline btn-block oauth-btn" type="button" @click="loginWithGoogle">
          <svg class="oauth-btn__icon" viewBox="0 0 18 18" aria-hidden="true">
            <path fill="#4285F4" d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.7-1.57 2.68-3.88 2.68-6.62z"/>
            <path fill="#34A853" d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.33A9 9 0 0 0 9 18z"/>
            <path fill="#FBBC05" d="M3.97 10.72A5.4 5.4 0 0 1 3.68 9c0-.6.1-1.18.29-1.72V4.95H.96A9 9 0 0 0 0 9c0 1.45.35 2.82.96 4.05l3.01-2.33z"/>
            <path fill="#EA4335" d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.59C13.46.89 11.43 0 9 0A9 9 0 0 0 .96 4.95l3.01 2.33C4.68 5.16 6.66 3.58 9 3.58z"/>
          </svg>
          使用 Google 登入
        </button>

        <p class="oauth-hint">
          使用 Google 登入代表授權本站讀取您 Google 帳號的 Email、姓名與大頭貼。
          首次登入會請您補上手機等基本資料以建立會員帳號；Google 僅作為一種登入方式，您的帳號與權限仍由本站管理。
        </p>
      </template>

      <div class="auth-card__links">
        <RouterLink to="/forgot-password">忘記密碼？</RouterLink>
        <span>還沒有帳號？<RouterLink to="/member-register">立即註冊</RouterLink></span>
        <span>暫時不繼續？<RouterLink to="/">返回首頁</RouterLink></span>
        <span class="auth-card__links--secondary">員工註冊&nbsp;&nbsp;<RouterLink to="/employee-register">提出員工申請</RouterLink></span>
        <span class="auth-card__links--secondary">員工申請未通過&nbsp;&nbsp;<RouterLink to="/employee-reapply">重新申請</RouterLink></span>
      </div>
    </section>

    <section v-if="showTestAccounts" class="test-accounts" aria-label="快捷登入">
      <h2 class="test-accounts__title">快捷登入（點擊自動填入）</h2>
      <ul class="test-accounts__list">
        <li v-for="account in TEST_ACCOUNTS" :key="account.email">
          <button type="button" class="test-accounts__item" @click="fillAccount(account)">
            <span class="test-accounts__role">{{ account.label }}</span>
            <span class="test-accounts__email">{{ wrapEmailAtAt(account.email) }}</span>
            <span class="test-accounts__password">
              {{ account.password ?? '（密碼請自行輸入）' }}
            </span>
          </button>
        </li>
      </ul>
    </section>
  </main>
</template>

<style scoped>
.auth-page {
  flex-direction: column;
  gap: 1.25rem;
}

/* 員工申請入口刻意弱化，避免跟登入頁主要動線（忘記密碼／會員註冊）搶視覺焦點 */
.auth-card__links--secondary {
  margin-top: 1rem;
  font-size: 0.75rem;
  line-height: 1.2;
  color: var(--text-muted);
}
.auth-card__links--secondary + .auth-card__links--secondary {
  margin-top: 0;
}

/* 帳號密碼登入與 Google 登入之間的分隔線 */
.oauth-divider {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin: 1.1rem 0;
  color: var(--text-muted);
  font-size: 0.8rem;
}
.oauth-divider::before,
.oauth-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--border);
}

.oauth-btn {
  gap: 0.6rem;
}
.oauth-btn__icon {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}

.oauth-hint {
  margin-top: 0.6rem;
  font-size: 0.72rem;
  line-height: 1.6;
  color: var(--text-muted);
}

.test-accounts {
  width: 100%;
  max-width: 26rem;
  padding: 1.1rem 1.4rem;
  background: var(--bg-section);
  border: 1px dashed var(--border);
  border-radius: var(--radius-md);
}

.test-accounts__title {
  margin: 0 0 0.75rem;
  font-size: 0.8rem;
  font-weight: 700;
  color: var(--text-muted);
}

.test-accounts__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.test-accounts__item {
  display: grid;
  grid-template-columns: 4.33rem 1fr; /* 原本 6.5rem 的 2/3 */
  gap: 0 0.5rem;
  width: 100%;
  padding: 0.5rem 0.65rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  text-align: left;
  font-size: 0.8rem;
  cursor: pointer;
  transition: var(--transition);
}

.test-accounts__item:hover {
  border-color: var(--accent);
  background: #fff;
}

.test-accounts__role {
  grid-row: span 2;
  align-self: center;
  text-align: center;
  font-weight: 600;
  color: var(--primary-dark);
  white-space: pre-line;
}

.test-accounts__email {
  color: var(--text-secondary);
  font-family: monospace;
}

.test-accounts__password {
  color: var(--text-muted);
  font-family: monospace;
}
</style>
