<script setup>
/**
 * 管理端人員列表的搜尋／篩選工具列。SearchFilter.vue 綁死了關鍵字＋日期區間，
 * 容不下 Employee 的 status/role 與 Trainer 的 status/deleted 篩選，故另立此元件。
 *
 * 只負責版面與事件，不打 API、不持有查詢狀態：
 *   - 關鍵字用 v-model 雙向綁定，呼叫端自行 debounce＋監看後重查
 *   - 其餘篩選欄位由呼叫端塞進 #filters 具名插槽（通常放 BaseSelect）
 *   - 「重置」只發 reset 事件，實際清空哪些狀態由呼叫端決定
 */
defineProps({
  keywordLabel: { type: String, default: '搜尋' },
  keywordPlaceholder: { type: String, default: '' },
  total: { type: Number, default: null },
  totalUnit: { type: String, default: '筆' },
})

const keyword = defineModel('keyword', { type: String, default: '' })
defineEmits(['reset'])
</script>

<template>
  <div class="toolbar card">
    <label class="toolbar__field toolbar__field--search">
      <span class="toolbar__label">{{ keywordLabel }}</span>
      <input
        v-model="keyword"
        type="search"
        class="toolbar__input"
        :placeholder="keywordPlaceholder"
      />
    </label>

    <slot name="filters" />

    <button type="button" class="btn btn-outline toolbar__reset" @click="$emit('reset')">重置</button>

    <span v-if="total !== null" class="toolbar__total">共 {{ total }} {{ totalUnit }}</span>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 1rem 1.25rem;
  padding: 1.25rem 1.5rem;
  margin-bottom: 1.5rem;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.toolbar__field {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.toolbar__label {
  font-size: 0.8rem;
  font-weight: 700;
  color: var(--text-secondary);
}

.toolbar__input {
  padding: 0.55rem 0.75rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.95rem;
  min-width: 9rem;
}

.toolbar__field--search .toolbar__input {
  min-width: 15rem;
}

/* 插槽內的欄位（BaseSelect 外層）也吃同一組欄位樣式，讓呼叫端不必重寫 */
.toolbar :deep(.toolbar__field) {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.toolbar :deep(.toolbar__label) {
  font-size: 0.8rem;
  font-weight: 700;
  color: var(--text-secondary);
}

.toolbar__reset {
  padding: 0.5rem 1.1rem;
}

.toolbar__total {
  margin-left: auto;
  font-size: 0.9rem;
  color: var(--text-secondary);
  white-space: nowrap;
}
</style>
