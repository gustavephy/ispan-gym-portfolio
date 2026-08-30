<template>
  <AppModal v-model:visible="visible" title="編輯私教課程內容" size="md">
    <form id="editPrivateCourseForm" @submit.prevent="handleSubmit">
      <div class="form-group">
        <label class="form-label">課程名稱</label>
        <input v-model="localCourse.name" class="form-input" required />
      </div>
      <div class="form-group" style="margin-top: 1rem;">
        <label class="form-label">課程堂數 (Session Per Unit)</label>
        <input v-model.number="localCourse.sessionPerUnit" type="number" class="form-input" min="1" required />
      </div>
      <div class="form-group" style="margin-top: 1rem;">
        <label class="form-label">課程售價</label>
        <input v-model.number="localCourse.price" type="number" class="form-input" min="0" required />
      </div>
      <div class="form-group" style="margin-top: 1rem;">
        <label class="form-label">上限人數</label>
        <input v-model.number="localCourse.maxCapacity" type="number" class="form-input" min="1" required />
      </div>
      <div class="form-group" style="margin-top: 1rem;">
        <label class="form-label">課程分類</label>
        <select v-model="localCourse.courseCategoryId" class="form-input" required>
          <option :value="null" disabled>請選擇分類</option>
          <option v-for="cat in categories" :key="cat.id" :value="cat.id">
            {{ cat.name }}
          </option>
        </select>
      </div>
      <div class="form-group" style="margin-top: 1rem;">
        <label class="form-label">指導教練</label>
        <select v-model="localCourse.trainerId" class="form-input" required>
          <option :value="null" disabled>請選擇指導教練</option>
          <option v-for="t in trainersList" :key="t.trainerId" :value="t.trainerId">
            {{ t.displayName }} ({{ t.specialty }})
          </option>
        </select>
      </div>
      <div class="form-group" style="margin-top: 1rem;">
        <label class="form-label">照片設定</label>
        <AppImageUpload v-model="localCourse.imageUrl" buttonText="重新上傳" placeholder="圖片位址 URL" />
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
      <AppButton type="button" variant="outline" class="btn-ghost" @click="$emit('update:visible', false)">取消</AppButton>
      <AppButton type="submit" form="editPrivateCourseForm" variant="primary" :disabled="saving">保存修改資料</AppButton>
    </template>
  </AppModal>
</template>

<script setup>
import { ref, watch } from 'vue'
import AppModal from '@/components/AppModal.vue'
import AppButton from '@/components/AppButton.vue'
import AppImageUpload from '@/components/AppImageUpload.vue'


const visible = defineModel('visible', { type: Boolean, default: false })

const props = defineProps({
  course: { type: Object, required: true },
  trainersList: { type: Array, default: () => [] },
  categories: { type: Array, default: () => [] },
  saving: { type: Boolean, default: false }
})

const emit = defineEmits(['submit'])

const localCourse = ref({ ...props.course })

watch(() => props.course, (newVal) => {
  localCourse.value = { ...newVal }
}, { deep: true })

// 圖片上傳已由 AppImageUpload 元件處理

function handleSubmit() {
  emit('submit', localCourse.value)
}
</script>
