<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '@/api/auth.js'
import { useAuthStore } from '@/stores/auth.js'

/**
 * 登入頁。同時也是「就地顯示錯誤」的示範：
 * /api/auth/login 屬於匿名端點，攔截器不會為它跳全域錯誤或導向登入頁，
 * 帳密錯誤等結果由這裡自己顯示在表單上。
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
  { label: '管理員', email: 'emp-admin@local.test', password: 'Admin@12345' },
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
    // 攔截器已正規化，直接取 message（後端會回「帳號或密碼錯誤」「員工帳號尚待審核」等具體說明）
    errorMessage.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login">
    <h1 class="login__title">登入</h1>

    <form class="login__form" @submit.prevent="onSubmit">
      <label class="login__field">
        <span>Email</span>
        <input v-model.trim="email" type="email" required autocomplete="email" />
      </label>

      <label class="login__field">
        <span>密碼</span>
        <input v-model="password" type="password" required autocomplete="current-password" />
      </label>

      <p v-if="errorMessage" class="login__error" role="alert">{{ errorMessage }}</p>

      <button class="login__submit" type="submit" :disabled="submitting">
        {{ submitting ? '登入中…' : '登入' }}
      </button>
    </form>

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
.login {
  max-width: 22rem;
  margin: 4rem auto;
  padding: 0 1rem;
}

.login__title {
  margin-bottom: 1.5rem;
  font-size: 1.5rem;
}

.login__form {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.login__field {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  font-size: 0.875rem;
}

.login__field input {
  padding: 0.5rem 0.65rem;
  border: 1px solid #ccc;
  border-radius: 0.35rem;
  font-size: 1rem;
}

.login__error {
  margin: 0;
  color: #c0392b;
  font-size: 0.875rem;
}

.login__submit {
  padding: 0.6rem;
  border: 0;
  border-radius: 0.35rem;
  background: #222;
  color: #fff;
  font-size: 1rem;
  cursor: pointer;
}

.login__submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.test-accounts {
  margin-top: 2rem;
  padding-top: 1.25rem;
  border-top: 1px dashed #ccc;
}

.test-accounts__title {
  margin: 0 0 0.75rem;
  font-size: 0.8rem;
  font-weight: 600;
  color: #888;
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
  border: 1px solid #e0e0e0;
  border-radius: 0.35rem;
  background: #fafafa;
  text-align: left;
  font-size: 0.8rem;
  cursor: pointer;
}

.test-accounts__item:hover {
  border-color: #999;
  background: #f0f0f0;
}

.test-accounts__role {
  grid-row: span 2;
  align-self: center;
  font-weight: 600;
}

.test-accounts__email {
  color: #444;
  font-family: monospace;
}

.test-accounts__password {
  color: #999;
  font-family: monospace;
}
</style>
