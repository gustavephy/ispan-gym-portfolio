<script setup>
defineOptions({ name: 'MemberProfileView' })
import { computed, inject, onActivated, onMounted, ref } from 'vue'
import { getMyProfile, updateMyProfile } from '@/api/memberProfile.js'
import OperationMessage from '@/components/OperationMessage.vue'
import BaseSelect from '@/components/BaseSelect.vue'

/**
 * 會員自助資料查看／修改頁，對應 GET/PUT /api/member/profile（MemberProfileController）。
 *
 * PUT 是完整覆蓋語意，不是「只送有改的欄位」：dob／gender／緊急聯絡人等選填欄位留空
 * 送出時要送 null 代表清除，不能省略不帶。不套用參考站常見的「生日必填／須滿 18 歲」
 * 規則——後端 DTO（MemberProfileUpdateRequest）本來就沒有這些限制，前端不多加。
 */

const showToast = inject('showToast')

const loading = ref(true)
const saving = ref(false)
const errorMessage = ref('')

const email = ref('')
const status = ref('')
const firstName = ref('')
const lastName = ref('')
const phone = ref('')
const dob = ref('')
const gender = ref('')
const emergencyContact = ref('')
const emergencyPhone = ref('')
const zipCode = ref('')
const city = ref('')
const district = ref('')
const address = ref('')

// 空字串代表「未填寫」，跟後端的 null 是同一件事，送出前會轉換
const GENDER_OPTIONS = [
  { label: '未填寫', value: '' },
  { label: '男', value: 'M' },
  { label: '女', value: 'F' },
  { label: '其他', value: 'OTHER' },
]

const STATUS_LABELS = { ACTIVE: '啟用中', SUSPENDED: '已停權', PENDING: '待驗證' }

const isPhoneValid = computed(() => /^09\d{8}$/.test(phone.value))
const isFormValid = computed(
  () => firstName.value.trim() !== '' && lastName.value.trim() !== '' && isPhoneValid.value,
)

function applyProfile(profile) {
  email.value = profile.email
  status.value = profile.status
  firstName.value = profile.firstName || ''
  lastName.value = profile.lastName || ''
  phone.value = profile.phone || ''
  dob.value = profile.dob || ''
  gender.value = profile.gender || ''
  emergencyContact.value = profile.emergencyContact || ''
  emergencyPhone.value = profile.emergencyPhone || ''
  zipCode.value = profile.zipCode || ''
  city.value = profile.city || ''
  district.value = profile.district || ''
  address.value = profile.address || ''
}

async function loadProfile() {
  try {
    const { data } = await getMyProfile()
    applyProfile(data)
  } catch (e) {
    errorMessage.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(loadProfile)

// DefaultLayout 用 <keep-alive> 快取這頁：離開再回來不會重新掛載，改完資料從別頁回來
// 會停在舊值。onActivated 在首次掛載也會觸發（那次由 onMounted 負責），用旗標略過。
let activatedBefore = false
onActivated(() => {
  if (activatedBefore) loadProfile()
  activatedBefore = true
})

async function onSubmit() {
  errorMessage.value = ''
  if (!isFormValid.value) {
    errorMessage.value = '請確認姓名與電話是否正確填寫'
    return
  }
  saving.value = true
  try {
    const payload = {
      firstName: firstName.value.trim(),
      lastName: lastName.value.trim(),
      phone: phone.value.trim(),
      dob: dob.value || null,
      gender: gender.value || null,
      emergencyContact: emergencyContact.value.trim() || null,
      emergencyPhone: emergencyPhone.value.trim() || null,
      zipCode: zipCode.value.trim() || null,
      city: city.value.trim() || null,
      district: district.value.trim() || null,
      address: address.value.trim() || null,
    }
    const { data } = await updateMyProfile(payload)
    applyProfile(data)
    showToast('會員資料已更新')
  } catch (e) {
    errorMessage.value = e.message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page-container">
    <div class="section-header">
      <span class="section-tag">MY PROFILE</span>
      <h2 class="section-title">會員資料</h2>
      <p class="section-desc">查看與修改您的會員個人資料</p>
    </div>

    <div v-if="loading" class="spinner-container">
      <div class="spinner"></div>
    </div>

    <div v-else class="profile-card">
      <div class="readonly-row">
        <div class="readonly-item">
          <span class="readonly-label">登入 Email</span>
          <span class="readonly-value">{{ email }}</span>
        </div>
        <div class="readonly-item">
          <span class="readonly-label">會員狀態</span>
          <span class="badge" :class="status === 'ACTIVE' ? 'badge-success' : 'badge-warning'">
            {{ STATUS_LABELS[status] || status }}
          </span>
        </div>
      </div>

      <form @submit.prevent="onSubmit">
        <div class="form-row">
          <div class="form-group">
            <label class="form-label" for="mp-lastName">姓氏</label>
            <input id="mp-lastName" v-model.trim="lastName" class="form-input" maxlength="50" required />
          </div>
          <div class="form-group">
            <label class="form-label" for="mp-firstName">名字</label>
            <input id="mp-firstName" v-model.trim="firstName" class="form-input" maxlength="50" required />
          </div>
        </div>

        <div class="form-group">
          <label class="form-label" for="mp-phone">聯絡電話</label>
          <input
            id="mp-phone"
            v-model.trim="phone"
            class="form-input"
            type="tel"
            maxlength="10"
            required
            autocomplete="tel"
            placeholder="0912345678"
          />
          <p v-if="phone && !isPhoneValid" class="form-error">
            電話格式不正確，請輸入台灣手機號碼，例如0912345678
          </p>
        </div>

        <div class="form-row">
          <div class="form-group">
            <label class="form-label" for="mp-dob">生日（選填）</label>
            <input id="mp-dob" v-model="dob" class="form-input" type="date" />
          </div>
          <div class="form-group">
            <label class="form-label" for="mp-gender">性別（選填）</label>
            <BaseSelect v-model="gender" :options="GENDER_OPTIONS" />
          </div>
        </div>

        <div class="form-row">
          <div class="form-group">
            <label class="form-label" for="mp-emergencyContact">緊急聯絡人（選填）</label>
            <input id="mp-emergencyContact" v-model.trim="emergencyContact" class="form-input" maxlength="50" />
          </div>
          <div class="form-group">
            <label class="form-label" for="mp-emergencyPhone">緊急聯絡電話（選填）</label>
            <input id="mp-emergencyPhone" v-model.trim="emergencyPhone" class="form-input" maxlength="20" />
          </div>
        </div>

        <div class="form-group">
          <label class="form-label" for="mp-zipCode">郵遞區號（選填）</label>
          <input id="mp-zipCode" v-model.trim="zipCode" class="form-input" maxlength="10" />
        </div>

        <div class="form-row">
          <div class="form-group">
            <label class="form-label" for="mp-city">縣市（選填）</label>
            <input id="mp-city" v-model.trim="city" class="form-input" maxlength="50" />
          </div>
          <div class="form-group">
            <label class="form-label" for="mp-district">鄉鎮市區（選填）</label>
            <input id="mp-district" v-model.trim="district" class="form-input" maxlength="50" />
          </div>
        </div>

        <div class="form-group">
          <label class="form-label" for="mp-address">詳細地址（選填）</label>
          <input id="mp-address" v-model.trim="address" class="form-input" maxlength="255" />
        </div>

        <OperationMessage :message="errorMessage" tone="error" />

        <button class="btn btn-primary" type="submit" :disabled="saving">
          {{ saving ? '儲存中…' : '儲存變更' }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.profile-card {
  max-width: 40rem;
  padding: 1.75rem;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
}

.readonly-row {
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem;
  padding-bottom: 1.25rem;
  margin-bottom: 1.25rem;
  border-bottom: 1px dashed var(--border);
}
.readonly-item {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
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

.form-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 0 1rem;
}

@media (max-width: 560px) {
  .form-row {
    grid-template-columns: 1fr;
  }
}
</style>
