<script setup>
/**
 * 操作結果就地顯示元件：給「列表按鈕操作」（取消、下架、核銷等非表單動作）用。
 * 跟 App.vue 的全域提示條不同，這裡不自動消失——使用者做下一個動作或重新整理頁面才會消失，
 * 讓使用者有足夠時間看清楚為什麼失敗。
 */
defineProps({
  message: { type: String, default: null },
  tone: { type: String, default: 'error' },
})
</script>

<template>
  <p v-if="message" class="operation-message" :class="`operation-message--${tone}`">
    {{ message }}
  </p>
</template>

<style scoped>
.operation-message {
  margin: 0 0 1rem;
  padding: 0.65rem 1rem;
  border: 1px solid var(--border);
  border-left: 3px solid currentColor;
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  color: var(--text-secondary);
  font-size: 0.9rem;
}

.operation-message--error {
  color: #b3452f;
}

.operation-message--warning {
  color: #b7791f;
}

.operation-message--success {
  color: #3f7a3a;
}

.operation-message--info {
  color: #38618c;
}
</style>
