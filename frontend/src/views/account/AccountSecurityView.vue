<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth.js'
import { getMyAccount, getOAuthConnections, changePassword, logoutAllDevices } from '@/api/account.js'
import { notify } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'
import AppModal from '@/components/AppModal.vue'

/**
 * 帳號設定頁：任何已登入身分都能用（不分 MEMBER/EMPLOYEE/ADMIN/TRAINER），對應
 * AccountController／PasswordController／OAuthConnectionController 三支後端 Controller
 * 共用的 /api/account/me 路徑前綴。
 *
 * Google 綁定狀態這裡只唯讀顯示——hasPassword 是登入方式狀態的單一來源，不在
 * AccountMeResponse 裡重複一份。綁定／解除綁定的實際操作是第五批「完整 OAuth
 * 帳號設定」的範圍，這裡不做。
 */

const router = useRouter()
const auth = useAuthStore()
const showToast = inject('showToast')

const loading = ref(true)
const loadError = ref('')

const account = ref(null) // AccountMeResponse
const connections = ref(null) // OAuthConnectionsResponse

const ROLE_LABELS = { MEMBER: '會員', EMPLOYEE: '員工', ADMIN: '管理員', TRAINER: '教練' }

function formatDateTime(value) {
  if (!value) return ''
  const d = new Date(value)
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`
}

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    const [accountRes, connectionsRes] = await Promise.all([getMyAccount(), getOAuthConnections()])
    account.value = accountRes.data
    connections.value = connectionsRes.data
    // 用最新結果刷新 auth store——例如帳號在登入後被停權/復權，roles 已經跟登入當下不同
    auth.syncAccount(accountRes.data)
  } catch (e) {
    loadError.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)

// === 修改密碼 ================================================================
// 密碼規則檢查沿用 EmployeeRegisterView.vue／ResetPasswordView.vue 既有的 inline
// computed 寫法，不另立共用 composable（跟那兩個頁面一樣各自各寫一份）。

const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const pwdErrorMessage = ref('')
const pwdSubmitting = ref(false)

const hasMinLength = computed(() => newPassword.value.length >= 8 && newPassword.value.length <= 72)
const hasUpperCase = computed(() => /[A-Z]/.test(newPassword.value))
const hasLowerCase = computed(() => /[a-z]/.test(newPassword.value))
const hasDigit = computed(() => /\d/.test(newPassword.value))
const hasNoWhitespace = computed(() => newPassword.value.length === 0 || !/\s/.test(newPassword.value))
// 後端 @ValidPassword 除了字元數 8~72，還要求 UTF-8 編碼後不得超過 72 bytes
const hasValidByteLength = computed(() => new TextEncoder().encode(newPassword.value).length <= 72)
const isNewPasswordValid = computed(
  () =>
    hasMinLength.value &&
    hasUpperCase.value &&
    hasLowerCase.value &&
    hasDigit.value &&
    hasNoWhitespace.value &&
    hasValidByteLength.value,
)
const isPasswordConfirmed = computed(
  () => confirmPassword.value.length > 0 && newPassword.value === confirmPassword.value,
)
const isChangePasswordFormValid = computed(
  () => currentPassword.value.length > 0 && isNewPasswordValid.value && isPasswordConfirmed.value,
)

async function onChangePassword() {
  pwdErrorMessage.value = ''
  if (!isChangePasswordFormValid.value) {
    pwdErrorMessage.value = '請確認表單各欄位是否正確填寫'
    return
  }
  pwdSubmitting.value = true
  try {
    const { data } = await changePassword({
      currentPassword: currentPassword.value,
      newPassword: newPassword.value,
      confirmPassword: confirmPassword.value,
    })
    auth.setSession(data) // 原地換發新 token，維持這個裝置登入；其他裝置已被登出
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    showToast('密碼已修改，其他裝置已自動登出')
  } catch (e) {
    pwdErrorMessage.value = e.message
  } finally {
    pwdSubmitting.value = false
  }
}

// === 登出此裝置／全裝置登出 =====================================================

function onLogoutThisDevice() {
  auth.logout()
  router.push({ name: 'login' })
}

const showLogoutAllConfirm = ref(false)
const loggingOutAll = ref(false)
const logoutAllError = ref('')

async function onConfirmLogoutAll() {
  loggingOutAll.value = true
  logoutAllError.value = ''
  try {
    await logoutAllDevices()
    showLogoutAllConfirm.value = false
    auth.logout()
    // 用全域事件而非本頁的 showToast：接下來會離開這個頁面（連同 DefaultLayout 一起卸載），
    // 頁面內的 toast 掛在 DefaultLayout 上，撐不過這次導頁；notify() 掛在 App.vue 才留得住。
    notify('已登出所有裝置', '請重新登入', 'success')
    router.push({ name: 'login' })
  } catch (e) {
    logoutAllError.value = e.message
  } finally {
    loggingOutAll.value = false
  }
}
</script>

<template>
  <div class="page-container">
    <div class="section-header">
      <span class="section-tag">ACCOUNT SETTINGS</span>
      <h2 class="section-title">帳號設定</h2>
      <p class="section-desc">查看帳號資訊、登入方式，並管理密碼與登入裝置</p>
    </div>

    <div v-if="loading" class="spinner-container">
      <div class="spinner"></div>
    </div>

    <OperationMessage v-else-if="loadError" :message="loadError" tone="error" />

    <div v-else class="security-sections">
      <!-- 帳號資訊 -->
      <section class="security-card">
        <h3 class="security-card__title">帳號資訊</h3>
        <div class="readonly-row">
          <div class="readonly-item">
            <span class="readonly-label">登入 Email</span>
            <span class="readonly-value">{{ account.email }}</span>
          </div>
          <div class="readonly-item">
            <span class="readonly-label">身分</span>
            <div class="role-badges">
              <span v-for="role in account.roles" :key="role" class="badge badge-primary">
                {{ ROLE_LABELS[role] || role }}
              </span>
            </div>
          </div>
          <div class="readonly-item">
            <span class="readonly-label">建立時間</span>
            <span class="readonly-value">{{ formatDateTime(account.createdAt) }}</span>
          </div>
        </div>
      </section>

      <!-- 登入方式 -->
      <section class="security-card">
        <h3 class="security-card__title">登入方式</h3>
        <div class="login-methods">
          <div class="login-method-row">
            <span class="login-method-name">本機密碼</span>
            <span class="badge" :class="connections.hasPassword ? 'badge-success' : 'badge-warning'">
              {{ connections.hasPassword ? '已設定' : '未設定' }}
            </span>
          </div>
          <div class="login-method-row">
            <span class="login-method-name">Google</span>
            <span class="badge" :class="connections.google.linked ? 'badge-success' : 'badge-warning'">
              {{ connections.google.linked ? `已綁定（${connections.google.email}）` : '未綁定' }}
            </span>
          </div>
        </div>
        <p class="security-card__note">Google 綁定／解除綁定功能將於後續批次提供。</p>
      </section>

      <!-- 修改密碼 -->
      <section class="security-card">
        <h3 class="security-card__title">修改密碼</h3>
        <p v-if="!connections.hasPassword" class="security-card__note">
          此帳號透過 Google 登入，尚未設定本機密碼，請使用原本的第三方登入方式。
        </p>
        <form v-else @submit.prevent="onChangePassword">
          <div class="form-group">
            <label class="form-label" for="sec-current-password">目前密碼</label>
            <input
              id="sec-current-password"
              v-model="currentPassword"
              class="form-input"
              type="password"
              required
              autocomplete="current-password"
            />
          </div>
          <div class="form-group">
            <label class="form-label" for="sec-new-password">新密碼</label>
            <input
              id="sec-new-password"
              v-model="newPassword"
              class="form-input"
              type="password"
              required
              autocomplete="new-password"
            />
            <ul v-if="newPassword" class="pwd-rules">
              <li :class="{ 'pwd-rules__item--pass': hasMinLength }">8～72 個字元</li>
              <li :class="{ 'pwd-rules__item--pass': hasUpperCase }">包含大寫英文字母</li>
              <li :class="{ 'pwd-rules__item--pass': hasLowerCase }">包含小寫英文字母</li>
              <li :class="{ 'pwd-rules__item--pass': hasDigit }">包含數字</li>
              <li :class="{ 'pwd-rules__item--pass': hasNoWhitespace }">不可包含空白字元</li>
              <li :class="{ 'pwd-rules__item--pass': hasValidByteLength }">UTF-8 編碼不超過 72 bytes</li>
            </ul>
          </div>
          <div class="form-group">
            <label class="form-label" for="sec-confirm-password">確認新密碼</label>
            <input
              id="sec-confirm-password"
              v-model="confirmPassword"
              class="form-input"
              type="password"
              required
              autocomplete="new-password"
            />
            <p v-if="confirmPassword && !isPasswordConfirmed" class="form-error">兩次密碼輸入不一致</p>
          </div>

          <OperationMessage :message="pwdErrorMessage" tone="error" />

          <button class="btn btn-primary" type="submit" :disabled="pwdSubmitting">
            {{ pwdSubmitting ? '送出中…' : '修改密碼' }}
          </button>
        </form>
      </section>

      <!-- 登入裝置 -->
      <section class="security-card">
        <h3 class="security-card__title">登入裝置</h3>
        <div class="device-actions">
          <div class="device-action-row">
            <div>
              <p class="device-action-title">登出此裝置</p>
              <p class="device-action-desc">只登出目前這個瀏覽器，其他裝置維持登入</p>
            </div>
            <button class="btn btn-edit" type="button" @click="onLogoutThisDevice">登出此裝置</button>
          </div>
          <div class="device-action-row">
            <div>
              <p class="device-action-title">全裝置登出</p>
              <p class="device-action-desc">登出所有裝置（含目前這個），下次需要重新輸入密碼登入</p>
            </div>
            <button class="btn btn-danger" type="button" @click="showLogoutAllConfirm = true">
              全裝置登出
            </button>
          </div>
        </div>
      </section>
    </div>

    <AppModal v-model:visible="showLogoutAllConfirm" title="確認全裝置登出" icon="⚠️">
      <p>
        此操作會立即讓所有裝置（含目前這個瀏覽器）的登入狀態失效，包含正在使用中的分頁，
        需要重新輸入密碼登入。確定要繼續嗎？
      </p>
      <OperationMessage :message="logoutAllError" tone="error" />
      <template #footer>
        <button
          class="btn btn-edit"
          type="button"
          :disabled="loggingOutAll"
          @click="showLogoutAllConfirm = false"
        >
          取消
        </button>
        <button class="btn btn-danger" type="button" :disabled="loggingOutAll" @click="onConfirmLogoutAll">
          {{ loggingOutAll ? '處理中…' : '確定登出所有裝置' }}
        </button>
      </template>
    </AppModal>
  </div>
</template>

<style scoped>
.security-sections {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
  max-width: 40rem;
}

.security-card {
  padding: 1.5rem;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
}

.security-card__title {
  font-size: 1.05rem;
  font-weight: 800;
  color: var(--primary-dark);
  margin: 0 0 1rem;
}

.security-card__note {
  margin: 0.75rem 0 0;
  font-size: 0.82rem;
  color: var(--text-muted);
}

.readonly-row {
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem;
}
.readonly-item {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}
.readonly-label {
  font-size: 0.72rem;
  font-weight: 700;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.readonly-value {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-primary);
}
.role-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.login-methods {
  display: flex;
  flex-direction: column;
  gap: 0.65rem;
}
.login-method-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}
.login-method-name {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-primary);
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
.pwd-rules li.pwd-rules__item--pass::before {
  content: '✓ ';
}

.device-actions {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.device-action-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}
.device-action-title {
  margin: 0;
  font-size: 0.9rem;
  font-weight: 700;
  color: var(--text-primary);
}
.device-action-desc {
  margin: 0.2rem 0 0;
  font-size: 0.78rem;
  color: var(--text-muted);
}

@media (max-width: 560px) {
  .pwd-rules {
    grid-template-columns: 1fr;
  }
  .device-action-row {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
