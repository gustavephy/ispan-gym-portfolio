<script setup>
defineProps({
  feedback: { type: Object, default: null }, // { text, type: 'success' | 'error' }
})
</script>

<template>
  <div v-if="feedback" class="feedback-toast" :class="feedback.type">{{ feedback.text }}</div>
</template>

<style scoped>
.feedback-toast {
  position: fixed;
  bottom: 2.5rem;
  left: 50%;
  transform: translateX(-50%);
  padding: 1rem 2.25rem;
  border-radius: 999px;
  font-size: 1.05rem;
  font-weight: 700;
  color: #fff;
  z-index: 1000;
  /* 陰影讓它從內容中浮起來——沒有陰影時，落在深色區塊上會糊成一片 */
  box-shadow: 0 0.5rem 1.5rem rgba(0, 0, 0, 0.22);
  /* 訊息可能較長（例如核銷碼），限制寬度並置中，不要撐滿整個視窗 */
  max-width: min(90vw, 34rem);
  text-align: center;
  animation: toast-in 0.22s ease-out;
}

@keyframes toast-in {
  from {
    opacity: 0;
    transform: translate(-50%, 0.75rem);
  }
  to {
    opacity: 1;
    transform: translate(-50%, 0);
  }
}

/* 使用者若在系統層關閉了動態效果，就只淡入不位移 */
@media (prefers-reduced-motion: reduce) {
  .feedback-toast {
    animation: none;
  }
}
.feedback-toast.success { background: #2f9e44; }
.feedback-toast.error { background: #e03131; }
</style>
