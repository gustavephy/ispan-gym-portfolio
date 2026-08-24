<template>
  <Transition name="fade">
    <div v-if="visible" class="modal-overlay" @click.self="emitClose">
      <div class="modal-content" :style="{ maxWidth: sizeWidth }">
        <div class="modal-header" style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.25rem;">
          <h3 class="modal-title" style="margin: 0; font-size: 1.15rem; font-weight: 700; color: var(--text-primary);">{{ title }}</h3>
          <button type="button" @click="emitClose" class="close-btn" style="background: none; border: none; font-size: 1.5rem; cursor: pointer; color: var(--text-secondary);">&times;</button>
        </div>
        <div class="modal-body" style="margin-bottom: 1.5rem;">
          <slot></slot>
        </div>
        <div class="modal-footer" style="display: flex; justify-content: flex-end; gap: 0.75rem;">
          <slot name="footer"></slot>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  title: {
    type: String,
    default: ''
  },
  size: {
    type: String,
    default: 'md'
  }
})

const emit = defineEmits(['update:visible'])

const sizeWidth = computed(() => {
  if (props.size === 'sm') return '360px'
  if (props.size === 'lg') return '720px'
  return '480px' // md
})

function emitClose() {
  emit('update:visible', false)
}
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.6);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  z-index: 999;
}

.modal-content {
  width: 100%;
  background: var(--bg-card, #ffffff);
  border: 1px solid var(--border, #e2e8f0);
  border-radius: var(--radius-md, 8px);
  box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.1), 0 8px 10px -6px rgba(0, 0, 0, 0.1);
  padding: 1.75rem;
  animation: modal-anim 0.3s ease-out;
}

@keyframes modal-anim {
  from {
    transform: scale(0.95);
    opacity: 0;
  }
  to {
    transform: scale(1);
    opacity: 1;
  }
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.25s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
