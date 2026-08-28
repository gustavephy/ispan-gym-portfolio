<template>
  <Teleport to="body">
    <Transition name="app-modal">
      <div
        v-if="visible"
        class="app-modal-overlay"
        @click.self="handleOverlayClick"
        role="dialog"
        :aria-label="title"
      >
        <div
          class="app-modal-box"
          :class="[`app-modal--${size}`]"
        >
          <!-- Header -->
          <div class="app-modal-header">
            <div class="app-modal-title-wrap">
              <span v-if="icon" class="app-modal-icon">{{ icon }}</span>
              <h3 class="app-modal-title">{{ title }}</h3>
            </div>
            <button class="app-modal-close" @click="close" type="button" aria-label="關閉">✕</button>
          </div>

          <!-- Body -->
          <div class="app-modal-body" :class="{ 'app-modal-body--compact': compact }">
            <slot />
          </div>

          <!-- Footer (optional slot) -->
          <div v-if="$slots.footer" class="app-modal-footer">
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'

defineOptions({ inheritAttrs: false })

// defineModel 取代手動的 prop + emit('update:visible') 模式 (Vue 3.4+)
const visible = defineModel('visible', { type: Boolean, default: false })

const props = defineProps({
  title: { type: String, default: '' },
  icon: { type: String, default: '' },
  //  'sm' = 420px | 'md' = 560px | 'lg' = 720px
  size: { type: String, default: 'md' },
  compact: { type: Boolean, default: false },
  //  點擊遮罩是否關閉
  closeOnOverlay: { type: Boolean, default: true }
})

const emit = defineEmits(['close'])

function close() {
  visible.value = false
  emit('close')
}

function handleOverlayClick() {
  if (props.closeOnOverlay) close()
}

function onKeyDown(e) {
  if (e.key === 'Escape' && visible.value) close()
}

onMounted(() => window.addEventListener('keydown', onKeyDown))
onUnmounted(() => window.removeEventListener('keydown', onKeyDown))
</script>

<style scoped>
/* ── Overlay ── */
.app-modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.55);
  backdrop-filter: blur(5px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 1.5rem;
}

/* ── Modal Box ── */
.app-modal-box {
  background: #ffffff;
  border-radius: 18px;
  box-shadow:
    0 4px 6px -1px rgba(0,0,0,0.07),
    0 20px 60px -10px rgba(44,76,62,0.22),
    0 0 0 1px rgba(44,76,62,0.06);
  display: flex;
  flex-direction: column;
  width: 100%;
  max-height: 90vh;
  overflow: hidden;
}

.app-modal--sm { max-width: 420px; }
.app-modal--md { max-width: 560px; }
.app-modal--lg { max-width: 720px; }

/* ── Header ── */
.app-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.1rem 1.5rem;
  border-bottom: 1px solid #e8e4dc;
  background: #faf9f7;
  flex-shrink: 0;
  gap: 1rem;
}

.app-modal-title-wrap {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.app-modal-icon {
  font-size: 1.1rem;
  line-height: 1;
}

.app-modal-title {
  font-size: 1rem;
  font-weight: 800;
  color: #1b3229;
  margin: 0;
  letter-spacing: -0.01em;
}

.app-modal-close {
  background: transparent;
  border: none;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  cursor: pointer;
  color: #6b7280;
  font-size: 0.85rem;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
  flex-shrink: 0;
}

.app-modal-close:hover {
  background: #f1f5f9;
  color: #1e221f;
}

/* ── Body ── */
.app-modal-body {
  padding: 1.5rem;
  overflow-y: auto;
  flex: 1;
  font-size: 0.9rem;
  color: #1e221f;
  line-height: 1.6;
}

.app-modal-body--compact {
  padding: 1rem 1.25rem;
}

/* ── Footer ── */
.app-modal-footer {
  padding: 0.9rem 1.5rem;
  border-top: 1px solid #e8e4dc;
  background: #faf9f7;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 0.6rem;
  flex-shrink: 0;
}

/* ── Transition ── */
.app-modal-enter-active,
.app-modal-leave-active {
  transition: opacity 0.2s ease;
}
.app-modal-enter-active .app-modal-box,
.app-modal-leave-active .app-modal-box {
  transition: transform 0.22s cubic-bezier(0.34, 1.56, 0.64, 1), opacity 0.2s ease;
}
.app-modal-enter-from,
.app-modal-leave-to {
  opacity: 0;
}
.app-modal-enter-from .app-modal-box {
  transform: scale(0.94) translateY(12px);
  opacity: 0;
}
.app-modal-leave-to .app-modal-box {
  transform: scale(0.96) translateY(8px);
  opacity: 0;
}
</style>
