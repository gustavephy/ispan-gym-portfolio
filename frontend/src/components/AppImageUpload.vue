<template>
  <div class="app-image-upload">
    <div class="file-upload-wrapper" style="display: flex; align-items: center; gap: 1rem; margin-bottom: 0.5rem; flex-wrap: wrap;">
      <AppButton type="button" variant="primary" style="padding: 0.5rem 1rem;" @click="fileInput.click()">
        {{ buttonText }}
      </AppButton>
      <input ref="fileInput" type="file" @change="handleFileChange" accept="image/*" style="display: none;" />
      
      <span v-if="uploadingFile" class="loading-spinner-inline"></span>
      <span class="file-status-text" v-if="uploadStatus" :style="{ color: uploadStatus.includes('去重') ? 'var(--primary, #5a4a42)' : '#10b981', fontWeight: 'bold' }">
        {{ uploadStatus }}
      </span>
      
      <img v-if="modelValue" :src="formatImageUrl(modelValue)" class="form-preview-thumb" alt="Preview" @click="zoomed = true" style="cursor: zoom-in; width: 90px; height: 90px; object-fit: cover; border-radius: 6px; border: 1px solid var(--border, #e2e8f0);" />
    </div>
    <input :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" class="form-input url-input" :placeholder="placeholder" style="width: 100%; margin-top: 0.5rem;" />

    <!-- 圖片放大預覽 Overlay -->
    <div v-if="zoomed && modelValue" class="zoomed-image-overlay" @click="zoomed = false">
      <div class="zoomed-image-container">
        <img :src="formatImageUrl(modelValue)" alt="Zoomed Preview" />
        <span class="close-overlay-btn">&times;</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, inject } from 'vue'
import AppButton from '@/components/AppButton.vue'
import { formatImageUrl, uploadImageFile } from '@/utils/image.js'

const props = defineProps({
  modelValue: { type: String, default: '' },
  buttonText: { type: String, default: '選擇照片' },
  placeholder: { type: String, default: '圖片位址 URL' }
})

const emit = defineEmits(['update:modelValue'])

const fileInput = ref(null)
const uploadingFile = ref(false)
const uploadStatus = ref('')
const zoomed = ref(false)
const showToast = inject('showToast', null)

watch(() => props.modelValue, (newVal) => {
  if (!newVal) {
    uploadStatus.value = ''
  }
})

async function handleFileChange(event) {
  const file = event.target.files[0]
  if (!file) return
  uploadingFile.value = true
  try {
    const result = await uploadImageFile(file)
    if (result && result.url) {
      emit('update:modelValue', result.url)
      if (result.duplicate || result.isDuplicate) {
        uploadStatus.value = '偵測到重複，已自動去重'
        if (showToast) showToast('圖片已上傳，偵測到重複，自動為您去重並引用既有檔案', 'success')
      } else {
        uploadStatus.value = '新圖片上傳成功'
        if (showToast) showToast('新圖片上傳成功！', 'success')
      }
    }
  } catch (e) {
    if (showToast) showToast(e.message || '上傳失敗', 'error')
    console.error(e)
  } finally {
    uploadingFile.value = false
    if (event && event.target) {
      event.target.value = ''
    }
  }
}
</script>

<style scoped>
.loading-spinner-inline {
  width: 1.5rem;
  height: 1.5rem;
  border: 3px solid rgba(0,0,0,0.1);
  border-radius: 50%;
  border-top-color: var(--primary, #5a4a42);
  animation: spin 1s ease-in-out infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

.zoomed-image-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.75);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 9999;
  cursor: zoom-out;
}
.zoomed-image-container {
  position: relative;
  max-width: 80%;
  max-height: 80%;
}
.zoomed-image-container img {
  max-width: 100%;
  max-height: 80vh;
  border-radius: 8px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.5);
  object-fit: contain;
}
.close-overlay-btn {
  position: absolute;
  top: -40px;
  right: 0;
  color: #fff;
  font-size: 2.5rem;
  cursor: pointer;
}
.file-status-text {
  font-size: 0.85rem;
}
</style>
