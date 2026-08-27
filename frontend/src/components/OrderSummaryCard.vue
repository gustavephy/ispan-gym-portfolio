<script setup>
// 金額摘要卡：購物車頁、結帳頁共用。
// 上半部（標題／明細列／合計）樣式統一；下半部（結帳按鈕、付款方式、備註等）
// 由使用端透過預設 slot 放入，額外的明細列可用 #rows slot 補。
defineProps({
  title: { type: String, default: '訂單摘要' },
  // [{ label, value }]；value 為數字時自動格式化為 NT$ 金額，字串則原樣顯示
  rows: { type: Array, default: () => [] },
  totalLabel: { type: String, default: '合計' },
  totalValue: { type: [Number, String], default: 0 },
  sticky: { type: Boolean, default: false },
})

function display(value) {
  return typeof value === 'number' ? `NT$ ${value.toLocaleString()}` : value
}
</script>

<template>
  <aside class="summary-card" :class="{ 'summary-card--sticky': sticky }">
    <h3 class="summary-card__title">{{ title }}</h3>

    <div v-for="row in rows" :key="row.label" class="summary-card__row">
      <span>{{ row.label }}</span>
      <span>{{ display(row.value) }}</span>
    </div>
    <slot name="rows" />

    <div class="summary-card__divider"></div>

    <div class="summary-card__row summary-card__row--total">
      <span>{{ totalLabel }}</span>
      <span class="summary-card__total">{{ display(totalValue) }}</span>
    </div>

    <slot />
  </aside>
</template>

<style scoped>
.summary-card {
  padding: 1.5rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-section);
}
.summary-card--sticky {
  position: sticky;
  top: 1.5rem;
}
.summary-card__title {
  margin-bottom: 1.2rem;
  font-size: 1.05rem;
  font-weight: 700;
}
.summary-card__row {
  display: flex;
  justify-content: space-between;
  margin-bottom: 0.75rem;
  font-size: 0.9rem;
  color: var(--text-secondary);
}
.summary-card__divider {
  height: 1px;
  margin: 1rem 0;
  background: var(--border);
}
.summary-card__row--total {
  margin-bottom: 0;
  font-size: 1rem;
  font-weight: 700;
  color: var(--text-primary);
}
.summary-card__total {
  font-size: 1.2rem;
  color: var(--accent-hover);
}
</style>
