<script setup>
// 空狀態共用元件：購物車、訂單、搜尋結果等「沒有資料」畫面
// icon 內建幾組線性圖示，特殊情況可用 #icon slot 覆寫；行動按鈕放 #action slot
defineProps({
  icon: { type: String, default: 'box' }, // 'cart' | 'orders' | 'search' | 'box'
  title: { type: String, required: true },
  description: { type: String, default: '' },
})
</script>

<template>
  <div class="empty-state">
    <div class="empty-state__icon" aria-hidden="true">
      <slot name="icon">
        <svg
          v-if="icon === 'cart'"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <circle cx="9" cy="20" r="1.4" />
          <circle cx="18" cy="20" r="1.4" />
          <path d="M2.5 3h2.6l2.2 11.4a1.8 1.8 0 0 0 1.8 1.4h8.3a1.8 1.8 0 0 0 1.8-1.4L22 7H6" />
        </svg>
        <svg
          v-else-if="icon === 'orders'"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <rect x="4" y="3" width="16" height="18" rx="2" />
          <path d="M8 8h8M8 12h8M8 16h5" />
        </svg>
        <svg
          v-else-if="icon === 'search'"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-3.6-3.6" />
        </svg>
        <svg
          v-else
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <path d="M3 8 12 3l9 5-9 5-9-5Z" />
          <path d="M3 8v8l9 5 9-5V8" />
        </svg>
      </slot>
    </div>

    <h2 class="empty-state__title">{{ title }}</h2>
    <p v-if="description" class="empty-state__desc">{{ description }}</p>

    <div v-if="$slots.action" class="empty-state__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 0.7rem;
  padding: 4rem 2rem;
  color: var(--text-muted);
}
.empty-state__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  margin-bottom: 0.25rem;
  border-radius: 50%;
  background: var(--bg-soft);
  color: var(--accent);
}
.empty-state__icon :deep(svg) {
  width: 34px;
  height: 34px;
}
.empty-state__title {
  margin: 0;
  font-size: 1.15rem;
  font-weight: 800;
  color: var(--text-primary);
}
.empty-state__desc {
  margin: 0;
  max-width: 22rem;
  font-size: 0.9rem;
  line-height: 1.6;
  color: var(--text-secondary);
}
.empty-state__action {
  margin-top: 0.75rem;
}
</style>
