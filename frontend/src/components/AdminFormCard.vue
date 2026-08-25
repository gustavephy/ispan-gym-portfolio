<template>
  <div class="card add-form-card">
    <div class="form-header">
      <h4>{{ title }}</h4>
      <span class="form-helper">{{ helper }}</span>
    </div>
    <form @submit.prevent="$emit('submit')">
      <!-- 格狀欄位 (預設插槽) -->
      <div class="form-grid-responsive">
        <slot />
      </div>
      <!-- 全寬欄位 (如 textarea、圖片上傳) -->
      <slot name="extra" />
      <div class="form-actions">
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
