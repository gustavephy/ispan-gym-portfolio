<template>
  <div class="card add-form-card" style="width: 100%; margin: 0 0 2rem 0; padding: 1.5rem; box-sizing: border-box;">
    <div class="form-header" style="margin-bottom: 1.25rem; padding-bottom: 0.75rem; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--border, #e8e0d9);">
      <h4 style="font-size: 1.05rem; margin: 0; font-weight: 700; color: var(--primary-dark, #3c302b);">{{ title }}</h4>
      <span class="form-helper" style="font-size: 0.75rem; color: #ef4444;">{{ helper }}</span>
    </div>
    <form @submit.prevent="$emit('submit')">
      <!-- 格狀欄位 (預設插槽) - 設為 5 欄以提供高彈性與寬欄位排版 -->
      <div style="display: grid; grid-template-columns: repeat(5, 1fr); gap: 0.75rem; align-items: start;">
        <slot />
      </div>
      <!-- 全寬欄位 (如 textarea、圖片上傳) -->
      <slot name="extra" />
      <div class="form-actions" style="margin-top: 1.5rem; display: flex; justify-content: flex-end;">
        <slot name="actions">
          <button type="submit" class="btn btn-primary">確認送出</button>
        </slot>
      </div>
    </form>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, required: true },
  helper: { type: String, default: '*所有欄位均為必填' }
})

defineEmits(['submit'])
</script>
