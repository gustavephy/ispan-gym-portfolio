<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '@/api/auth.js'
import { useAuthStore } from '@/stores/auth.js'
import OperationMessage from '@/components/OperationMessage.vue'

/**
 * 登入頁。同時也是「就地顯示錯誤」的示範：
 * /api/auth/login 屬於匿名端點（見 api/auth.js 的 ANONYMOUS_CONFIG），
 * 攔截器不會為它跳全域錯誤或導向登入頁，帳密錯誤等結果由這裡自己顯示在表單上。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const email = ref('')
const password = ref('')
const errorMessage = ref('')
const submitting = ref(false)

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
  { label: '管理員（種子）', email: 'admin@wpgym.local', password: 'Admin@12345' },
]

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

      <div class="auth-card__links">
        <RouterLink to="/forgot-password">忘記密碼？</RouterLink>
        <span>還沒有帳號？<RouterLink to="/register">立即註冊</RouterLink></span>
        <span class="auth-card__links--secondary">員工註冊&nbsp;&nbsp;<RouterLink to="/employee-register">提出員工申請</RouterLink></span>
        <span class="auth-card__links--secondary">員工申請未通過&nbsp;&nbsp;<RouterLink to="/employee-reapply">重新申請</RouterLink></span>
      </div>
    </section>

    <section v-if="showTestAccounts" class="test-accounts" aria-label="測試帳號">
      <h2 class="test-accounts__title">測試帳號（點擊自動填入）</h2>
      <ul class="test-accounts__list">
        <li v-for="account in TEST_ACCOUNTS" :key="account.email">
          <button type="button" class="test-accounts__item" @click="fillAccount(account)">
            <span class="test-accounts__role">{{ account.label }}</span>
            <span class="test-accounts__email">{{ account.email }}</span>
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
  margin-top: 0.4rem;
  font-size: 0.75rem;
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
  grid-template-columns: 6.5rem 1fr;
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
  font-weight: 600;
  color: var(--primary-dark);
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
