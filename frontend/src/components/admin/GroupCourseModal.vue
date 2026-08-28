<template>
  <AppModal v-model:visible="visible" title="編輯團體課程內容" size="md">
    <form id="editGroupCourseForm" @submit.prevent="handleSubmit">
      <div class="form-group">
        <label class="form-label">課程名稱</label>
        <input v-model="localCourse.name" class="form-input" required />
      </div>
      <div class="form-grid-responsive" style="display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 1rem; margin-top: 1rem;">
        <div class="form-group">
          <label class="form-label">指導教練</label>
          <select v-model="localCourse.trainerId" class="form-input" required>
            <option :value="null" disabled>請選擇指導教練</option>
            <option v-for="t in trainersList" :key="t.trainerId" :value="t.trainerId">
              {{ t.displayName }} ({{ t.specialty }})
            </option>
          </select>
        </div>
        <div class="form-group">
          <label class="form-label">每班上限人數</label>
          <input v-model.number="localCourse.maxCapacity" type="number" class="form-input" min="1" max="100" required />
        </div>
        <div class="form-group">
          <label class="form-label">課程分類</label>
          <select v-model="localCourse.categoryId" class="form-input" required>
            <option :value="null" disabled>請選擇分類</option>
            <option v-for="cat in categories" :key="cat.id" :value="cat.id">
              {{ cat.name }}
            </option>
          </select>
        </div>
      </div>
      <div class="form-group" style="margin-top: 1rem;">
        <label class="form-label">課程照片</label>
        <div class="file-upload-wrapper" style="display: flex; align-items: center; gap: 1rem; margin-bottom: 0.5rem;">
          <label class="custom-file-btn" style="background: var(--primary, #5a4a42); color: #ffffff; padding: 0.5rem 1rem; border-radius: 6px; cursor: pointer; display: inline-block;">
            重新上傳
            <input type="file" @change="uploadFile" accept="image/*" style="display: none;" />
          </label>
          <span v-if="uploadingFile" class="loading-spinner-inline"></span>
          <img v-if="localCourse.imageUrl" :src="formatImageUrl(localCourse.imageUrl)" class="form-preview-thumb" alt="Preview" style="width: 90px; height: 90px; object-fit: cover; border-radius: 6px; border: 1px solid var(--border, #e2e8f0);" />
        </div>
        <input v-model="localCourse.imageUrl" class="form-input url-input" placeholder="圖片位址 URL" style="width: 100%; margin-top: 0.5rem;" />
      </div>
      <div class="form-group" style="margin-top: 1rem;">
        <label class="form-label">詳細說明</label>
        <textarea v-model="localCourse.description" class="form-input" rows="3" style="width: 100%; padding: 0.5rem; box-sizing: border-box;"></textarea>
      </div>
      <div class="form-group checkbox-group" style="display:flex; align-items:center; justify-content:center; min-height: 38px; margin-top: 1rem;">
        <label class="checkbox-container" style="display: flex; align-items: center; gap: 0.5rem; cursor: pointer;">
          <input v-model="localCourse.isTrial" type="checkbox" class="custom-checkbox" />
          <span class="checkmark"></span>
          <span class="checkbox-label-text">開放免費體驗</span>
        </label>
      </div>
    </form>
    <template #footer>
      <button type="button" class="btn btn-ghost" @click="visible = false">取消</button>
      <button type="submit" form="editGroupCourseForm" class="btn btn-primary" :disabled="saving">保存修改資料</button>
    </template>
  </AppModal>
</template>

<script setup>
import { ref, watch } from 'vue'
import AppModal from '@/components/AppModal.vue'
import api from '@/api/axios.instance.js'
import { formatImageUrl } from '@/utils/image.js'

const visible = defineModel('visible', { type: Boolean, default: false })

const props = defineProps({
  course: { type: Object, required: true },
  trainersList: { type: Array, default: () => [] },
  categories: { type: Array, default: () => [] },
  saving: { type: Boolean, default: false }
})

const emit = defineEmits(['submit'])

const localCourse = ref({ ...props.course })
const uploadingFile = ref(false)

watch(() => props.course, (newVal) => {
  localCourse.value = { ...newVal }
}, { deep: true })

async function uploadFile(event) {
  const file = event.target.files[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    alert('照片檔案不能超過 5MB')
    return
  }
  uploadingFile.value = true
  try {
    const formData = new FormData()
    formData.append('file', file)
    const res = await api.post('/api/admin/products/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    const url = res.url || res.data?.url
    if (url) {
      localCourse.value.imageUrl = url
    }
  } catch (e) {
    console.error('Upload failed:', e)
  } finally {
    uploadingFile.value = false
  }
}

function handleSubmit() {
  emit('submit', localCourse.value)
}
</script>
