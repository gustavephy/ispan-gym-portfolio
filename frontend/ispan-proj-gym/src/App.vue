<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { RouterView, useRouter } from 'vue-router'
import { onApiErrors } from '@/api/error-bus.js'

/**
 * 全域錯誤呈現。攔截器只負責發事件，這裡決定怎麼顯示與導向，
 * 兩者解耦，之後換成元件庫的 Toast 只要改這個檔案。
 */
const router = useRouter()
const DISMISS_MS = 6000

const banner = ref(null)
// 連續兩次錯誤內容可能相同，key 遞增才能重新觸發進場動畫與倒數條
const bannerKey = ref(0)
let dismissTimer = null

function show(title, message, tone = 'error') {
  banner.value = { title, message, tone }
  bannerKey.value++
  restartDismissTimer()
}

function restartDismissTimer() {
  clearTimeout(dismissTimer)
  dismissTimer = setTimeout(() => (banner.value = null), DISMISS_MS)
}

function dismiss() {
  clearTimeout(dismissTimer)
  banner.value = null
}

/** 滑鼠懸停時暫停自動消失（使用者正在讀），離開後重新給滿倒數並重跑倒數條 */
function pauseDismiss() {
  clearTimeout(dismissTimer)
}
function resumeDismiss() {
  if (!banner.value) return
  bannerKey.value++ // 倒數條動畫重頭跑，與重設的計時器一致
  restartDismissTimer()
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

    // 任意組件經 notify() 發出的一般通知（成功／資訊等），沿用同一條提示條
    onNotice: ({ title, message, tone }) => show(title, message, tone),
  })
})

onUnmounted(() => {
  clearTimeout(dismissTimer)
  unsubscribe?.()
})
</script>

<template>
  <Transition name="banner">
    <div
      v-if="banner"
      :key="bannerKey"
      class="app-banner"
      :class="`app-banner--${banner.tone}`"
      role="alert"
      @mouseenter="pauseDismiss"
      @mouseleave="resumeDismiss"
    >
      <div class="app-banner__body">
        <strong class="app-banner__title">{{ banner.title }}</strong>
        <span class="app-banner__message">{{ banner.message }}</span>
      </div>
      <button class="app-banner__close" type="button" aria-label="關閉" @click="dismiss">×</button>
      <div
        class="app-banner__progress"
        :style="{ animationDuration: `${DISMISS_MS}ms` }"
        aria-hidden="true"
      ></div>
    </div>
  </Transition>

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
  /* 短訊息也維持一定份量，長訊息最寬 44rem；小螢幕一律留 1rem 邊距 */
  min-width: min(26rem, calc(100vw - 2rem));
  max-width: min(44rem, calc(100vw - 2rem));
  padding: 0.85rem 1.15rem 0.95rem;
  border-radius: 0.5rem;
  border-left: 4px solid currentColor;
  box-shadow: 0 4px 16px rgb(0 0 0 / 0.15);
  background: #fff;
  overflow: hidden; /* 讓倒數條的圓角跟著容器裁切 */
}

/* 色調＝左側色條、標題與倒數條的顏色；內文一律深灰維持可讀性 */
.app-banner--error {
  color: #c0392b;
}

.app-banner--warning {
  color: #b7791f;
}

.app-banner--success {
  color: #2e7d4f;
}

.app-banner--info {
  color: #2a6bb0;
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

/* 自動消失倒數條：沿底部由滿收縮到零，與 DISMISS_MS 計時器同步；
   懸停時暫停（計時器同時由 JS 清除），離開後由 key 重掛重頭跑 */
.app-banner__progress {
  position: absolute;
  left: 0;
  bottom: 0;
  height: 3px;
  width: 100%;
  background: currentColor;
  opacity: 0.35;
  transform-origin: left;
  animation: banner-countdown linear forwards;
}

.app-banner:hover .app-banner__progress {
  animation-play-state: paused;
}

@keyframes banner-countdown {
  from { transform: scaleX(1); }
  to { transform: scaleX(0); }
}

/* 進場：自頂端滑下並帶一點回彈；退場：上浮淡出（比進場快，讓畫面盡快讓位） */
.banner-enter-active {
  animation: banner-in 0.38s cubic-bezier(0.34, 1.4, 0.64, 1);
}

.banner-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.banner-leave-to {
  opacity: 0;
  transform: translate(-50%, -0.75rem);
}

@keyframes banner-in {
  from {
    opacity: 0;
    transform: translate(-50%, -1.25rem) scale(0.97);
  }
  to {
    opacity: 1;
    transform: translate(-50%, 0) scale(1);
  }
}

/* 偏好減少動態的使用者：直接顯示與消失，倒數條保留（提示消失時間屬於資訊而非裝飾） */
@media (prefers-reduced-motion: reduce) {
  .banner-enter-active {
    animation: none;
  }
  .banner-leave-active {
    transition: none;
  }
}
</style>
