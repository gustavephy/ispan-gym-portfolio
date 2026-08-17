<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { RouterView, useRouter } from 'vue-router'
import { onApiErrors } from '@/api/error-bus.js'

/**
 * 全域錯誤呈現。攔截器只負責發事件，這裡決定怎麼顯示與導向，
 * 兩者解耦，之後換成元件庫的 Toast 只要改這個檔案。
 */
const router = useRouter()
const banner = ref(null)
let dismissTimer = null

function show(title, message, tone = 'error') {
  banner.value = { title, message, tone }
  clearTimeout(dismissTimer)
  dismissTimer = setTimeout(() => (banner.value = null), 6000)
}

function dismiss() {
  clearTimeout(dismissTimer)
  banner.value = null
}

let unsubscribe = null

onMounted(() => {
  unsubscribe = onApiErrors({
    onError: (e) => show('系統執行錯誤', e.message),

    onSessionExpired: (e) => {
      show('請重新登入', e.message)
      // 攔截器已清除 token，這裡只負責導向。用 router 而非 window.location，
      // 避免整頁重載並保留原本要去的頁面以便登入後導回。
      router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
        .catch(() => {})
    },

    onSubscriptionRequired: (e) => {
      show('需要訂閱', e.message, 'warning')
      router.push('/subscription').catch(() => {})
    },
  })
})

onUnmounted(() => {
  clearTimeout(dismissTimer)
  unsubscribe?.()
})
</script>

<template>
  <div v-if="banner" class="app-banner" :class="`app-banner--${banner.tone}`" role="alert">
    <div class="app-banner__body">
      <strong class="app-banner__title">{{ banner.title }}</strong>
      <span class="app-banner__message">{{ banner.message }}</span>
    </div>
    <button class="app-banner__close" type="button" aria-label="關閉" @click="dismiss">×</button>
  </div>

  <RouterView />
</template>

<style scoped>
.app-banner {
  position: fixed;
  top: 1rem;
  left: 50%;
  transform: translateX(-50%);
  z-index: 1000;
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  max-width: min(32rem, calc(100vw - 2rem));
  padding: 0.75rem 1rem;
  border-radius: 0.5rem;
  border-left: 4px solid currentColor;
  box-shadow: 0 4px 16px rgb(0 0 0 / 0.15);
  background: #fff;
}

.app-banner--error {
  color: #c0392b;
}

.app-banner--warning {
  color: #b7791f;
}

.app-banner__body {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}

.app-banner__title {
  font-size: 0.9rem;
}

.app-banner__message {
  color: #333;
  font-size: 0.875rem;
  line-height: 1.5;
}

.app-banner__close {
  margin-left: auto;
  border: 0;
  background: none;
  font-size: 1.25rem;
  line-height: 1;
  cursor: pointer;
  color: inherit;
}
</style>
