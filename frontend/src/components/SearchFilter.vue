<template>
  <div class="search-filter-card card" style="padding: 1.25rem; margin-bottom: 1.5rem; background: var(--bg-card, #ffffff); border: 1px solid var(--border, #e2e8f0); border-radius: var(--radius-md, 8px);">
    <div style="display: flex; gap: 1.25rem; flex-wrap: wrap; align-items: flex-end;">
      <!-- 關鍵字搜尋 -->
      <div style="flex: 2; min-width: 200px;">
        <label class="form-label" style="display: block; margin-bottom: 0.5rem; font-size: 0.85rem; font-weight: 700; color: var(--text-secondary, #64748b);">{{ keywordLabel }}</label>
        <input 
          v-model="localValue.keyword" 
          type="text" 
          class="form-input" 
          :placeholder="keywordPlaceholder" 
          style="width: 100%;" 
        />
      </div>

      <!-- 日期範圍 -->
      <div style="flex: 1.5; min-width: 150px;">
        <label class="form-label" style="display: block; margin-bottom: 0.5rem; font-size: 0.85rem; font-weight: 700; color: var(--text-secondary, #64748b);">{{ dateLabel }} (起)</label>
        <input 
          v-model="localValue.startDate" 
          type="date" 
          class="form-input" 
          style="width: 100%;" 
        />
      </div>

      <div style="flex: 1.5; min-width: 150px;">
        <label class="form-label" style="display: block; margin-bottom: 0.5rem; font-size: 0.85rem; font-weight: 700; color: var(--text-secondary, #64748b);">{{ dateLabel }} (迄)</label>
        <input 
          v-model="localValue.endDate" 
          type="date" 
          class="form-input" 
          style="width: 100%;" 
        />
      </div>

      <!-- 功能按鈕 -->
      <div style="display: flex; gap: 0.5rem;">
        <button type="button" @click="emitSearch" class="btn btn-primary" style="padding: 0.55rem 1.25rem;">搜尋</button>
        <button type="button" @click="emitReset" class="btn btn-outline" style="padding: 0.55rem 1.25rem;">重置</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  modelValue: {
    type: Object,
    default: () => ({ keyword: '', startDate: '', endDate: '' })
  },
  keywordLabel: {
    type: String,
    default: '關鍵字搜尋'
  },
  keywordPlaceholder: {
    type: String,
    default: '搜尋...'
  },
  dateLabel: {
    type: String,
    default: '上架日期範圍'
  }
})

const emit = defineEmits(['update:modelValue', 'search', 'reset'])

const localValue = ref({
  keyword: props.modelValue.keyword || '',
  startDate: props.modelValue.startDate || '',
  endDate: props.modelValue.endDate || ''
})

watch(() => props.modelValue, (newVal) => {
  if (newVal) {
    localValue.value = {
      keyword: newVal.keyword || '',
      startDate: newVal.startDate || '',
      endDate: newVal.endDate || ''
    }
  }
}, { deep: true })

watch(localValue, (newVal) => {
  emit('update:modelValue', newVal)
}, { deep: true })

function emitSearch() {
  emit('search', localValue.value)
}

function emitReset() {
  localValue.value = { keyword: '', startDate: '', endDate: '' }
  emit('reset')
}
</script>
