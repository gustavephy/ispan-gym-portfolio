<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import api from '@/api/axios.instance.js'
import OperationMessage from '@/components/OperationMessage.vue'

import { formatImageUrl } from '@/utils/image.js'

const showToast = inject('showToast')

const loading = ref(true)
const saving = ref(false)
const errorMessage = ref('')

const email = ref('')
const status = ref('')
const firstName = ref('')
const lastName = ref('')
const phone = ref('')
const role = ref('')
const hireDate = ref('')
const isTrainer = ref(false)

// 教練欄位
const profileImageUrl = ref('')
const specialty = ref('')
const experience = ref('')
const bio = ref('')
const uploadingFile = ref(false)

const STATUS_LABELS = { 
  ACTIVE: '在職中', 
  SUSPENDED: '已停權', 
  PENDING: '待審核',
  REJECTED: '已拒絕'
}

const ROLE_LABELS = { 
  EMPLOYEE: '一般員工', 
  ADMIN: '系統管理員', 
  TRAINER: '教練' 
}

const isPhoneValid = computed(() => /^09\d{8}$/.test(phone.value))
const isFormValid = computed(() => firstName.value.trim() !== '' && lastName.value.trim() !== '' && isPhoneValid.value)

function applyProfile(profile) {
  email.value = profile.email
  status.value = profile.status
  firstName.value = profile.firstName || ''
  lastName.value = profile.lastName || ''
  phone.value = profile.phone || ''
  role.value = profile.role || ''
  hireDate.value = profile.hireDate || ''
  isTrainer.value = profile.isTrainer || false
  
  // 教練資料
  profileImageUrl.value = profile.profileImageUrl || ''
  specialty.value = profile.specialty || ''
  experience.value = profile.experience || ''
  bio.value = profile.bio || ''
}

onMounted(async () => {
  try {
    const { data } = await api.get('/api/employee/profile')
    applyProfile(data)
  } catch (e) {
    errorMessage.value = e.message
  } finally {
    loading.value = false
  }
})

async function uploadFile(event) {
  const file = event.target.files[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    if (showToast) showToast('頭像照片檔案不能超過 5MB', 'error')
    return
  }
  uploadingFile.value = true
  try {
    const formData = new FormData()
    formData.append('file', file)
    // 使用員工專用的個人頭像上傳端點
    const res = await api.post('/api/employee/profile/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    const url = res.url || res.data?.url
    if (url) {
      profileImageUrl.value = url
    }
  } catch (e) {
    console.error(e)
    if (showToast) showToast('上傳失敗', 'error')
  } finally {
    uploadingFile.value = false
  }
}

async function onSubmit() {
  errorMessage.value = ''
  if (!isFormValid.value) {
    errorMessage.value = '請確認姓名與電話是否正確填寫 (電話需為09開頭10位數字)'
    return
  }
  saving.value = true
  try {
    const payload = {
      lastName: lastName.value.trim(),
      firstName: firstName.value.trim(),
      phone: phone.value.trim(),
      profileImageUrl: isTrainer.value ? profileImageUrl.value : null,
      specialty: isTrainer.value ? specialty.value.trim() : null,
      experience: isTrainer.value ? experience.value.trim() : null,
      bio: isTrainer.value ? bio.value.trim() : null
    }
    const { data } = await api.put('/api/employee/profile', payload)
    applyProfile(data)
    showToast('基本資料已更新')
    window.dispatchEvent(new CustomEvent('profile-updated'))
  } catch (e) {
    errorMessage.value = e.message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page-container">
    <span class="section-tag">PROFILE</span>
    <h2 class="section-title">員工基本資料</h2>
    <p class="section-desc">檢視您的在職資訊並管理聯絡電話。</p>

    <OperationMessage v-if="errorMessage" tone="error" class="mb-4">
      {{ errorMessage }}
    </OperationMessage>

    <div v-if="loading" class="loading-state">
      <div class="spinner"></div>
      <p>資料載入中...</p>
    </div>

    <form v-else @submit.prevent="onSubmit" class="profile-form">
      <div class="form-grid">
        <!-- 唯讀人事資訊 -->
        <div class="card readonly-card">
          <h3 class="card-title">人事登錄資訊</h3>

          <div class="info-row">
            <span class="info-label">帳號 Email</span>
            <span class="info-val">{{ email }}</span>
          </div>

          <div class="info-row">
            <span class="info-label">目前職位</span>
            <span class="info-val">{{ ROLE_LABELS[role] || role }}</span>
          </div>

          <div class="info-row">
            <span class="info-label">教練身分</span>
            <span class="info-val">{{ isTrainer ? '是 (已綁定)' : '否' }}</span>
          </div>

          <div class="info-row">
            <span class="info-label">到職日期</span>
            <span class="info-val">{{ hireDate || '未登錄' }}</span>
          </div>

          <div class="info-row">
            <span class="info-label">在職狀態</span>
            <span class="status-badge" :class="`status-${status?.toLowerCase()}`">
              {{ STATUS_LABELS[status] || status }}
            </span>
          </div>
        </div>

        <!-- 聯絡資訊與基本資料修改 -->
        <div class="card form-card">
          <h3 class="card-title">基本資料與聯絡資訊</h3>

          <div class="form-row" style="display: flex; gap: 1rem; margin-bottom: 1rem;">
            <div class="form-group" style="flex: 1;">
              <label for="lastName" class="form-label">姓氏 <span class="required">*</span></label>
              <input
                id="lastName"
                v-model="lastName"
                type="text"
                placeholder="例如: 王"
                class="form-control"
                required
              />
            </div>
            <div class="form-group" style="flex: 1;">
              <label for="firstName" class="form-label">名字 <span class="required">*</span></label>
              <input
                id="firstName"
                v-model="firstName"
                type="text"
                placeholder="例如: 小明"
                class="form-control"
                required
              />
            </div>
          </div>

          <div class="form-group" style="margin-bottom: 1rem;">
            <label for="phone" class="form-label">聯絡電話 <span class="required">*</span></label>
            <input
              id="phone"
              v-model="phone"
              type="tel"
              placeholder="例如: 0912345678"
              class="form-control"
              :class="{ 'is-invalid': phone && !isPhoneValid }"
              required
            />
            <span v-if="phone && !isPhoneValid" class="field-error">請輸入正確的 10 位手機格式</span>
          </div>

          <!-- 移除原本在卡片內部的按鈕 -->
        </div>
 
        <!-- 教練專用基本資料 (頭像、專長、經歷、簡介) -->
        <div v-if="isTrainer" class="card trainer-card" style="grid-column: span 2;">
          <h3 class="card-title">教練專屬公開資訊</h3>
          
          <div style="display: flex; gap: 2rem; flex-wrap: wrap;">
            <!-- 頭像上傳區 -->
            <div style="flex: 1; min-width: 250px; display: flex; flex-direction: column; align-items: center; justify-content: center; border-right: 1px solid var(--border); padding-right: 2rem;">
              <label class="form-label" style="align-self: flex-start; margin-bottom: 1rem;">個人頭像照片</label>
              <div class="avatar-preview-wrapper" style="position: relative; width: 140px; height: 140px; border-radius: 50%; overflow: hidden; border: 3px solid var(--accent); margin-bottom: 1rem; background: var(--bg-body);">
                <img :src="profileImageUrl ? formatImageUrl(profileImageUrl) : '/default-avatar.png'" style="width: 100%; height: 100%; object-fit: cover;" alt="Avatar" />
                <div v-if="uploadingFile" style="position: absolute; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; color: white; font-size: 0.8rem;">上傳中...</div>
              </div>
              <label class="btn-submit" style="cursor: pointer; text-align: center; font-size: 0.85rem; padding: 0.5rem 1rem;">
                上傳頭像照片
                <input type="file" @change="uploadFile" accept="image/*" style="display: none;" />
              </label>
            </div>
 
            <!-- 文字資料區 -->
            <div style="flex: 2; min-width: 300px; display: flex; flex-direction: column; gap: 1rem;">
              <div class="form-group">
                <label for="specialty" class="form-label">教練專長 <span class="required">*</span></label>
                <input id="specialty" v-model="specialty" type="text" placeholder="例如: 核心訓練、重量訓練、瑜珈" class="form-control" :required="isTrainer" />
              </div>
              
              <div class="form-group">
                <label for="experience" class="form-label">教學經歷 <span class="required">*</span></label>
                <textarea id="experience" v-model="experience" rows="3" placeholder="例如: 1. 健身房個人教練 3 年&#10;2. 瑜珈會館指導老師" class="form-control" :required="isTrainer" style="resize: vertical;"></textarea>
              </div>
 
              <div class="form-group">
                <label for="bio" class="form-label">個人簡介 <span class="required">*</span></label>
                <textarea id="bio" v-model="bio" rows="3" placeholder="請用簡短、吸引人的文字介紹你自己..." class="form-control" :required="isTrainer" style="resize: vertical;"></textarea>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 全局表單送出按鈕 -->
      <div class="form-actions" style="margin-top: 2rem; display: flex; justify-content: flex-end;">
        <button
          type="submit"
          class="btn-submit"
          style="padding: 0.75rem 2.5rem; font-size: 1rem;"
          :disabled="saving || !isFormValid"
        >
          {{ saving ? '儲存中...' : '儲存變更' }}
        </button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  padding: 4rem 0;
  color: var(--text-secondary);
}

.spinner {
  width: 40px;
  height: 40px;
  border: 4px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.profile-form {
  max-width: 1000px;
  margin-top: 1.5rem;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1.5rem;
}

@media (max-width: 768px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}

.card {
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 1.5rem;
  box-shadow: 0 4px 12px rgba(60, 48, 43, 0.03);
}

.card-title {
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--primary-dark);
  margin-bottom: 1.25rem;
  border-bottom: 1px solid var(--border);
  padding-bottom: 0.5rem;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 0;
  border-bottom: 1px dashed var(--border);
}

.info-row:last-child {
  border-bottom: none;
}

.info-label {
  font-size: 0.9rem;
  color: var(--text-secondary);
  font-weight: 500;
}

.info-val {
  font-size: 0.9rem;
  color: var(--text-primary);
  font-weight: 600;
}

.status-badge {
  font-size: 0.75rem;
  font-weight: 700;
  padding: 0.2rem 0.6rem;
  border-radius: 999px;
}

.status-active {
  background: #ebfbee;
  color: #2b8a3e;
}

.status-suspended {
  background: #fff5f5;
  color: #c92a2a;
}

.status-pending {
  background: #fff9db;
  color: #e67700;
}

/* 表單欄位 */
.form-group {
  margin-bottom: 1.25rem;
}

.form-label {
  display: block;
  font-size: 0.9rem;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 0.4rem;
}

.required {
  color: #e03131;
}

.form-control {
  width: 100%;
  padding: 0.6rem 0.8rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-body);
  font-size: 0.9rem;
  color: var(--text-primary);
  transition: var(--transition);
}

.form-control:focus {
  outline: none;
  border-color: var(--accent);
  background: var(--bg-section);
  box-shadow: 0 0 0 3px rgba(201, 154, 91, 0.15);
}

.form-control.is-invalid {
  border-color: #f03e3e;
  background-color: #fff5f5;
}

.field-error {
  display: block;
  font-size: 0.75rem;
  color: #f03e3e;
  margin-top: 0.3rem;
  font-weight: 600;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 1.5rem;
}

.btn-submit {
  padding: 0.6rem 1.5rem;
  background: var(--accent);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 0.9rem;
  font-weight: 700;
  cursor: pointer;
  transition: var(--transition);
}

.btn-submit:hover:not(:disabled) {
  background: var(--accent-hover);
}

.btn-submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.mb-4 {
  margin-bottom: 1rem;
}
</style>
