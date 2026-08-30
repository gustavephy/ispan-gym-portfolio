<template>
  <button
    :class="['btn', `btn-${variant}`, { 'btn-block': block }]"
    :disabled="disabled || loading"
    :type="type"
  >
    <span v-if="loading" class="spinner-inline"></span>
    <span v-else class="btn-content">
      <slot />
    </span>
  </button>
</template>

<script setup>
defineProps({
  variant: {
    type: String,
    default: 'accent' // primary, accent, outline, danger, edit
  },
  disabled: {
    type: Boolean,
    default: false
  },
  loading: {
    type: Boolean,
    default: false
  },
  block: {
    type: Boolean,
    default: false
  },
  type: {
    type: String,
    default: 'button'
  }
})
</script>

<style scoped>
.spinner-inline {
  display: inline-block;
  width: 1rem;
  height: 1rem;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: rotate 0.75s linear infinite;
  vertical-align: text-bottom;
}
@keyframes rotate {
  to { transform: rotate(360deg); }
}
.btn-content {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.3rem;
}
</style>
