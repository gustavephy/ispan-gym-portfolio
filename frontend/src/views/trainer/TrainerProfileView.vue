<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { getMyTrainerProfile, updateMyTrainerProfile } from '@/api/trainerProfile.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'
import StaffStatusBadge from '@/components/StaffStatusBadge.vue'
import StaffChangeConfirmModal from '@/components/StaffChangeConfirmModal.vue'

/**
 * 教練自助「專業資料」頁，對應 GET/PUT /api/trainer/profile（TrainerProfileController）。
 *
 * 只管專業資料（專長／經歷／自我介紹／個人圖片網址）。一般個資（姓名／電話）走
 * 員工資料頁；Email／密碼走帳號安全頁。目前不做圖片上傳，profileImageUrl 只能貼網址。
 * UNAVAILABLE（暫停服務）教練仍可正常查改——可服務狀態與教練身分本身脫鉤。
 *
 * 送出採二次確認：共用 StaffChangeConfirmModal，與 Admin 員工／教練管理、員工自助頁一致。
 */

const showToast = inject('showToast')

const LIMITS = { specialty: 255, experience: 1000, bio: 5000, profileImageUrl: 255 }

const FIELD_LABELS = {
  specialty: '專長',
  experience: '經歷',
  bio: '自我介紹',
  profileImageUrl: '個人圖片網址',
}

const loading = ref(true)
const saving = ref(false)
const loadError = ref('')
const formError = ref('')
const confirmError = ref('')

const profile = ref(null)
const form = ref({ specialty: '', experience: '', bio: '', profileImageUrl: '' })
const confirmModal = ref(false)
const pendingPayload = ref(null)

const isValid = computed(
  () =>
    form.value.specialty.trim() !== '' &&
    form.value.experience.trim() !== '' &&
    form.value.bio.trim() !== '' &&
    form.value.specialty.length <= LIMITS.specialty &&
    form.value.experience.length <= LIMITS.experience &&
    form.value.bio.length <= LIMITS.bio &&
    form.value.profileImageUrl.length <= LIMITS.profileImageUrl,
)

function normalizedForm() {
  return {
    specialty: form.value.specialty.trim(),
    experience: form.value.experience.trim(),
    bio: form.value.bio.trim(),
    // 後端允許 null／空白代表清除
    profileImageUrl: form.value.profileImageUrl.trim() || null,
  }
}

const pendingChanges = computed(() => {
  if (!profile.value || !pendingPayload.value) return []
  return Object.keys(FIELD_LABELS)
    .filter((key) => (profile.value[key] || null) !== pendingPayload.value[key])
    .map((key) => ({
      key,
      label: FIELD_LABELS[key],
      before: profile.value[key] || '',
      after: pendingPayload.value[key] || '',
    }))
})

const isDirty = computed(() => {
  if (!profile.value) return false
  const payload = normalizedForm()
  return Object.keys(FIELD_LABELS).some(
    (key) => (profile.value[key] || null) !== payload[key],
  )
})

function apply(data) {
  profile.value = data
  form.value = {
    specialty: data.specialty || '',
    experience: data.experience || '',
    bio: data.bio || '',
    profileImageUrl: data.profileImageUrl || '',
  }
}

onMounted(async () => {
  try {
    const { data } = await getMyTrainerProfile()
    apply(data)
  } catch (e) {
    loadError.value = e.message
  } finally {
    loading.value = false
  }
})

function onSubmit() {
  formError.value = ''
  if (!isValid.value) {
    formError.value = '請確認專長、經歷、自我介紹皆已填寫且長度符合限制'
    return
  }
  if (!isDirty.value) {
    formError.value = '目前沒有需要儲存的變更'
    return
  }
  confirmError.value = ''
  pendingPayload.value = normalizedForm()
  confirmModal.value = true
}

async function confirmUpdate() {
  if (!pendingPayload.value || pendingChanges.value.length === 0) return
  saving.value = true
  confirmError.value = ''
  try {
    const { data } = await updateMyTrainerProfile(pendingPayload.value)
    apply(data)
    confirmModal.value = false
    pendingPayload.value = null
    showToast('教練資料已更新')
  } catch (e) {
    if (isInlineError(e)) confirmError.value = e.message
    else {
      confirmModal.value = false
      notifyApiError(e)
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page-container">
    <span class="section-tag">TRAINER</span>
    <h1 class="section-title">教練資料</h1>
    <p class="section-desc">維護您對外顯示的專業資料。姓名與電話請至「員工資料」頁修改。</p>

    <div v-if="loading" class="spinner"></div>

    <OperationMessage v-else-if="loadError" :message="loadError" />

    <div v-else-if="profile" class="profile-card card">
      <dl class="readonly-grid">
        <div>
          <dt>教練編號</dt>
          <dd>#{{ profile.trainerId }}</dd>
        </div>
        <div>
          <dt>可服務狀態</dt>
          <dd><StaffStatusBadge kind="trainerStatus" :value="profile.status" /></dd>
        </div>
      </dl>

      <form class="edit-form" @submit.prevent="onSubmit">
        <div class="form-group">
          <label class="form-label" for="tp-specialty">專長</label>
          <input
            id="tp-specialty"
            v-model="form.specialty"
            class="form-input"
            :maxlength="LIMITS.specialty"
            placeholder="例如：重量訓練、體態雕塑"
            required
          />
        </div>

        <div class="form-group">
          <label class="form-label" for="tp-experience">經歷</label>
          <textarea
            id="tp-experience"
            v-model="form.experience"
            class="form-input"
            rows="3"
            :maxlength="LIMITS.experience"
            required
          ></textarea>
          <p class="char-count">{{ form.experience.length }} / {{ LIMITS.experience }}</p>
        </div>

        <div class="form-group">
          <label class="form-label" for="tp-bio">自我介紹</label>
          <textarea
            id="tp-bio"
            v-model="form.bio"
            class="form-input"
            rows="5"
            :maxlength="LIMITS.bio"
            required
          ></textarea>
          <p class="char-count">{{ form.bio.length }} / {{ LIMITS.bio }}</p>
        </div>

        <div class="form-group">
          <label class="form-label" for="tp-image">個人圖片網址（選填）</label>
          <input
            id="tp-image"
            v-model="form.profileImageUrl"
            class="form-input"
            type="url"
            :maxlength="LIMITS.profileImageUrl"
            placeholder="https://..."
          />
          <p class="char-count">目前不提供上傳，請貼入圖片網址。</p>
        </div>

        <OperationMessage :message="formError" tone="error" />

        <button class="btn btn-primary" type="submit" :disabled="saving || !isValid || !isDirty">
          儲存變更
        </button>
      </form>
    </div>

    <StaffChangeConfirmModal
      v-model:visible="confirmModal"
      title="確認修改教練資料"
      intro="請確認以下變更，送出後將更新對外顯示的教練資料。"
      :changes="pendingChanges"
      :saving="saving"
      :error-message="confirmError"
      @confirm="confirmUpdate"
    />
  </div>
</template>

<style scoped>
.profile-card {
  max-width: 42rem;
  padding: 1.75rem;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.readonly-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: 1rem 1.5rem;
  margin: 0 0 1.5rem;
  padding-bottom: 1.5rem;
  border-bottom: 1px dashed var(--border);
}

.readonly-grid div {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}

.readonly-grid dt {
  font-size: 0.72rem;
  font-weight: 700;
  color: var(--text-muted);
  letter-spacing: 0.05em;
}

.readonly-grid dd {
  margin: 0;
  font-size: 0.92rem;
  font-weight: 600;
  color: var(--text-primary);
}

.edit-form {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.char-count {
  margin: 0.3rem 0 0;
  font-size: 0.78rem;
  color: var(--text-muted);
}
</style>
