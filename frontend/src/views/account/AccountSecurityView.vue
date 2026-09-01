<script setup>
defineOptions({ name: 'AccountSecurityView' })
import { computed, inject, onActivated, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth.js'
import {
  getMyAccount,
  getOAuthConnections,
  changePassword,
  setPassword,
  linkGoogle,
  unlinkGoogle,
  logoutAllDevices,
} from '@/api/account.js'
import { linkAuthorizationUrl } from '@/api/oauth.js'
import { notify } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'
import AppModal from '@/components/AppModal.vue'

/**
 * 帳號設定頁：任何已登入身分都能用（不分 MEMBER/EMPLOYEE/ADMIN/TRAINER），對應
 * AccountController／PasswordController／OAuthConnectionController 三支後端 Controller
 * 共用的 /api/account/me 路徑前綴。
 *
 * 第五批（完整 OAuth 帳號設定）在此頁加入：Google 綁定／解除綁定、OAuth-only 帳號
 * 設定第一組本地密碼。綁定發起會整頁導向 Google，成功／失敗由 /account/oauth/callback
 * 落地後 notify() 一則訊息並 replace 回本頁，本頁重新掛載時以 loadAll() 重查權威狀態。
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

// DefaultLayout 用 <keep-alive> 快取這頁：從 Google 連結回呼頁回來要看到最新連結狀態，重新抓。
// onActivated 在首次掛載也會觸發（那次由 onMounted 負責），用旗標略過。
// 注意：不要假設一定由 onActivated 觸發——綁定流程是整頁導向 Google，回來時 keep-alive
// 快取已不存在，走的是 onMounted。兩條路徑都會 loadAll()。
let activatedBefore = false
onActivated(() => {
  if (activatedBefore) loadAll()
  activatedBefore = true
})

// === 新密碼規則（修改密碼與設定第一組密碼共用同一組 computed） ===================
// 兩個表單由 connections.hasPassword 互斥顯示（v-if / v-else），共用 newPassword／
// confirmPassword 兩個 ref 不會互相干擾。密碼規則檢查沿用 EmployeeRegisterView.vue／
// ResetPasswordView.vue 既有的 inline computed 寫法，不另立共用 composable。

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

function resetPasswordForm() {
  currentPassword.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
}

// === 修改密碼（已有本地密碼的帳號）===========================================

const isChangePasswordFormValid = computed(
  () => currentPassword.value.length > 0 && isNewPasswordValid.value && isPasswordConfirmed.value,
)

async function onChangePassword() {
  if (pwdSubmitting.value) return // 按鈕 disabled 擋不住 Enter 連按，這裡再擋一次重入
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
    resetPasswordForm()
    showToast('密碼已修改，其他裝置已自動登出')
  } catch (e) {
    pwdErrorMessage.value = e.message
  } finally {
    pwdSubmitting.value = false
  }
}

// === 設定第一組本地密碼（OAuth-only 帳號：connections.hasPassword === false）======

const isSetPasswordFormValid = computed(() => isNewPasswordValid.value && isPasswordConfirmed.value)

async function onSetPassword() {
  if (pwdSubmitting.value) return // 同 onChangePassword：擋 Enter 連按造成的重入
  pwdErrorMessage.value = ''
  if (!isSetPasswordFormValid.value) {
    pwdErrorMessage.value = '請確認密碼符合規則且兩次輸入一致'
    return
  }
  pwdSubmitting.value = true
  try {
    const { data } = await setPassword({
      newPassword: newPassword.value,
      confirmPassword: confirmPassword.value,
    })
    // 後端已 credentialsVersion + 1、簽發新 JWT：原地換發，讓這個裝置以新 token 繼續登入
    auth.setSession(data)
    resetPasswordForm()
    // 重查權威狀態：hasPassword 變 true → 畫面切換成「修改密碼」，解除 Google 綁定按鈕開放
    await loadAll()
    showToast('已設定本地密碼，之後可用 Email 與密碼登入')
  } catch (e) {
    pwdErrorMessage.value = e.message
  } finally {
    pwdSubmitting.value = false
  }
}

// === Google 綁定／解除綁定 ====================================================

/** 把後端錯誤 code 對應成給使用者看的文案（重新驗證失敗不會清登入，見 axios 攔截器） */
function oauthActionErrorText(e) {
  switch (e.code) {
    case 'REAUTHENTICATION_REQUIRED':
      return e.message || '目前密碼錯誤，請重新輸入'
    case 'OAUTH_ALREADY_LINKED':
      return '此帳號已綁定 Google，請重新整理頁面'
    case 'OAUTH_LINK_RATE_LIMITED':
      return '發起綁定過於頻繁，請稍後再試'
    case 'LAST_LOGIN_METHOD':
      return 'Google 是目前唯一的登入方式，請先設定本地密碼後再解除綁定'
    case 'HTTP_404':
      return 'Google 綁定功能目前無法使用'
    default:
      return e.message || '操作失敗，請稍後再試'
  }
}

const linkModalOpen = ref(false)
const linkPassword = ref('')
const linkSubmitting = ref(false)
const linkError = ref('')

function openLinkModal() {
  linkPassword.value = ''
  linkError.value = ''
  linkModalOpen.value = true
}

async function onConfirmLink() {
  if (linkSubmitting.value) return // Modal 密碼欄綁 @keyup.enter，按鈕 disabled 擋不住連按 Enter
  linkError.value = ''
  if (!linkPassword.value) {
    linkError.value = '請輸入目前密碼'
    return
  }
  linkSubmitting.value = true
  try {
    const { data } = await linkGoogle(linkPassword.value)
    const url = linkAuthorizationUrl(data.authorizationUrl)
    if (!url) {
      linkError.value = '無法取得 Google 綁定網址，請稍後再試'
      return
    }
    // 整頁導向 Google；回來時後端導到 /account/oauth/callback
    window.location.href = url
  } catch (e) {
    linkError.value = oauthActionErrorText(e)
  } finally {
    linkSubmitting.value = false
  }
}

const unlinkModalOpen = ref(false)
const unlinkPassword = ref('')
const unlinkSubmitting = ref(false)
const unlinkError = ref('')

function openUnlinkModal() {
  unlinkPassword.value = ''
  unlinkError.value = ''
  unlinkModalOpen.value = true
}

async function onConfirmUnlink() {
  if (unlinkSubmitting.value) return // 同 onConfirmLink：擋 Enter 連按（第一筆成功後第二筆會拿失效 JWT 再送）
  unlinkError.value = ''
  if (!unlinkPassword.value) {
    unlinkError.value = '請輸入目前密碼'
    return
  }
  unlinkSubmitting.value = true
  try {
    await unlinkGoogle(unlinkPassword.value)
    unlinkModalOpen.value = false
    // 後端不核發新 JWT，credentialsVersion 已 +1：所有裝置（含這台）都需重新登入
    auth.logout()
    notify('已解除 Google 綁定', '所有裝置都需要重新登入', 'success')
    router.push({ name: 'login' })
  } catch (e) {
    unlinkError.value = oauthActionErrorText(e)
  } finally {
    unlinkSubmitting.value = false
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
            <div class="login-method-info">
              <span class="login-method-name">Google</span>
              <span v-if="connections.google.linked" class="login-method-sub">
                {{ connections.google.email }}
                <template v-if="connections.google.linkedAt">
                  · 綁定於 {{ formatDateTime(connections.google.linkedAt) }}
                </template>
              </span>
            </div>
            <div class="login-method-actions">
              <span class="badge" :class="connections.google.linked ? 'badge-success' : 'badge-warning'">
                {{ connections.google.linked ? '已綁定' : '未綁定' }}
              </span>
              <button
                v-if="!connections.google.linked"
                class="btn btn-edit btn-sm"
                type="button"
                @click="openLinkModal"
              >
                綁定 Google
              </button>
              <button
                v-else
                class="btn btn-danger btn-sm"
                type="button"
                :disabled="!connections.hasPassword"
                @click="openUnlinkModal"
              >
                解除綁定
              </button>
            </div>
          </div>
        </div>
        <p v-if="connections.google.linked && !connections.hasPassword" class="security-card__note">
          Google 是目前唯一的登入方式，請先於下方設定本地密碼後才能解除綁定。
        </p>
        <p v-else class="security-card__note">
          綁定 Google 後可用 Google 帳號快速登入；綁定的 Google Email 必須與本站登入 Email 相同。
        </p>
      </section>

      <!-- 本地密碼：已設定 → 修改密碼；未設定（OAuth-only）→ 設定第一組密碼 -->
      <section class="security-card">
        <h3 class="security-card__title">{{ connections.hasPassword ? '修改密碼' : '設定本地密碼' }}</h3>

        <p v-if="!connections.hasPassword" class="security-card__note set-password-intro">
          此帳號目前透過 Google 登入。設定本地密碼後，也能用 Email 與密碼登入；設定完成會登出其他裝置。
        </p>

        <form @submit.prevent="connections.hasPassword ? onChangePassword() : onSetPassword()">
          <div v-if="connections.hasPassword" class="form-group">
            <label class="form-label" for="sec-current-password">目前密碼</label>
            <input
              id="sec-current-password"
              v-model="currentPassword"
              class="form-input"
              type="password"
              required
              autocomplete="current-password"
              :disabled="pwdSubmitting"
            />
          </div>
          <div class="form-group">
            <label class="form-label" for="sec-new-password">
              {{ connections.hasPassword ? '新密碼' : '密碼' }}
            </label>
            <input
              id="sec-new-password"
              v-model="newPassword"
              class="form-input"
              type="password"
              required
              autocomplete="new-password"
              :disabled="pwdSubmitting"
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
            <label class="form-label" for="sec-confirm-password">
              {{ connections.hasPassword ? '確認新密碼' : '確認密碼' }}
            </label>
            <input
              id="sec-confirm-password"
              v-model="confirmPassword"
              class="form-input"
              type="password"
              required
              autocomplete="new-password"
              :disabled="pwdSubmitting"
            />
            <p v-if="confirmPassword && !isPasswordConfirmed" class="form-error">兩次密碼輸入不一致</p>
          </div>

          <OperationMessage :message="pwdErrorMessage" tone="error" />

          <button
            class="btn btn-primary"
            type="submit"
            :disabled="
              pwdSubmitting ||
              (connections.hasPassword ? !isChangePasswordFormValid : !isSetPasswordFormValid)
            "
          >
            {{
              pwdSubmitting
                ? '送出中…'
                : connections.hasPassword
                  ? '修改密碼'
                  : '設定本地密碼'
            }}
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

    <!-- 綁定 Google：重新驗證目前密碼 -->
    <AppModal v-model:visible="linkModalOpen" title="綁定 Google 帳號" icon="🔗">
      <p>
        為確認是本人操作，請輸入目前的本地密碼。接著會前往 Google 完成授權，
        綁定的 Google Email 必須與本站登入 Email（{{ account?.email }}）相同。
      </p>
      <div class="form-group">
        <label class="form-label" for="link-current-password">目前密碼</label>
        <input
          id="link-current-password"
          v-model="linkPassword"
          class="form-input"
          type="password"
          autocomplete="current-password"
          :disabled="linkSubmitting"
          @keyup.enter="onConfirmLink"
        />
      </div>
      <OperationMessage :message="linkError" tone="error" />
      <template #footer>
        <button class="btn btn-edit" type="button" :disabled="linkSubmitting" @click="linkModalOpen = false">
          取消
        </button>
        <button class="btn btn-primary" type="button" :disabled="linkSubmitting" @click="onConfirmLink">
          {{ linkSubmitting ? '前往 Google…' : '前往 Google 授權' }}
        </button>
      </template>
    </AppModal>

    <!-- 解除 Google 綁定：重新驗證 + 危險操作確認 -->
    <AppModal v-model:visible="unlinkModalOpen" title="解除 Google 綁定" icon="⚠️">
      <p>
        解除後將無法再用 Google 登入此帳號。此操作會立即讓
        <strong>所有裝置（含目前這個瀏覽器）</strong>的登入狀態失效，需重新以 Email 與密碼登入。
      </p>
      <div class="form-group">
        <label class="form-label" for="unlink-current-password">目前密碼</label>
        <input
          id="unlink-current-password"
          v-model="unlinkPassword"
          class="form-input"
          type="password"
          autocomplete="current-password"
          :disabled="unlinkSubmitting"
          @keyup.enter="onConfirmUnlink"
        />
      </div>
      <OperationMessage :message="unlinkError" tone="error" />
      <template #footer>
        <button
          class="btn btn-edit"
          type="button"
          :disabled="unlinkSubmitting"
          @click="unlinkModalOpen = false"
        >
          取消
        </button>
        <button
          class="btn btn-danger"
          type="button"
          :disabled="unlinkSubmitting"
          @click="onConfirmUnlink"
        >
          {{ unlinkSubmitting ? '處理中…' : '確定解除綁定' }}
        </button>
      </template>
    </AppModal>

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

.set-password-intro {
  margin: 0 0 1rem;
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
  gap: 0.9rem;
}
.login-method-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}
.login-method-info {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}
.login-method-name {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-primary);
}
.login-method-sub {
  font-size: 0.78rem;
  color: var(--text-muted);
}
.login-method-actions {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  flex-shrink: 0;
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
  .login-method-row {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
