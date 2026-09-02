<script setup>
// 綠界結帳轉導的全屏過場。掛在 DefaultLayout 一次，讀 useEcpayCheckout 的模組層 state。
import { RouterLink } from 'vue-router'
import { useEcpayCheckout } from '@/composables/useEcpayCheckout.js'

const { state, dismissTimeout } = useEcpayCheckout()
</script>

<template>
  <Teleport to="body">
    <div
      v-if="state.redirecting || state.timedOut"
      class="ecpay-overlay"
      role="alert"
      aria-live="assertive"
    >
      <div class="ecpay-overlay__panel">
        <template v-if="state.redirecting">
          <div class="spinner"></div>
          <p class="ecpay-overlay__title">正在前往綠界安全付款頁</p>
          <p class="ecpay-overlay__desc">請稍候，不要關閉或重新整理此頁…</p>
        </template>
        <template v-else>
          <p class="ecpay-overlay__title">似乎沒有順利前往綠界</p>
          <p class="ecpay-overlay__desc">
            訂單已建立。若沒有自動跳轉，可至「我的訂單」重新前往付款。
          </p>
          <div class="ecpay-overlay__actions">
            <RouterLink to="/orders" class="btn btn-accent" @click="dismissTimeout"
              >前往我的訂單</RouterLink
            >
            <button type="button" class="btn btn-outline" @click="dismissTimeout">關閉</button>
          </div>
        </template>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.ecpay-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  background: rgba(0, 0, 0, 0.5);
}
.ecpay-overlay__panel {
  width: 100%;
  max-width: 360px;
  padding: 2rem 1.75rem;
  border-radius: var(--radius-md);
  background: var(--bg-section);
  box-shadow: var(--shadow-hover);
  text-align: center;
}
.ecpay-overlay__panel .spinner {
  margin: 0 auto 1.25rem;
}
.ecpay-overlay__title {
  font-size: 1rem;
  font-weight: 700;
  color: var(--text-primary);
}
.ecpay-overlay__desc {
  margin-top: 0.5rem;
  font-size: 0.85rem;
  line-height: 1.6;
  color: var(--text-secondary);
}
.ecpay-overlay__actions {
  display: flex;
  gap: 0.6rem;
  margin-top: 1.25rem;
}
.ecpay-overlay__actions .btn {
  flex: 1;
}
</style>
