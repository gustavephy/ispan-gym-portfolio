<script setup>
/**
 * 共用分頁控制項：每頁筆數下拉選單＋上一頁/下一頁＋跳頁輸入框。純展示＋事件，
 * 不自己打 API、不持有頁碼狀態——page/size 由父層透過 props 傳入、
 * 呼叫端監聽 update:page／update:size 自行重新查詢。
 *
 * totalPages 為 0（查無資料）時不特別處理文字，父層應自行顯示 empty-state，
 * 這裡只負責分頁控制項本身（此時上一頁/下一頁/跳頁輸入框皆為 disabled）。
 */
import { ref, watch } from 'vue'

const props = defineProps({
  page: { type: Number, required: true },
  totalPages: { type: Number, required: true },
  size: { type: Number, required: true },
  sizeOptions: { type: Array, default: () => [5, 10, 15, 20] },
})

const emit = defineEmits(['update:page', 'update:size'])

const jumpInput = ref(String(props.page + 1))

watch(
  () => props.page,
  (newPage) => {
    jumpInput.value = String(newPage + 1)
  },
)

function goPrev() {
  if (props.page <= 0) return
  emit('update:page', props.page - 1)
}

function goNext() {
  if (props.page >= props.totalPages - 1) return
  emit('update:page', props.page + 1)
}

function handleSizeChange(event) {
  emit('update:size', Number(event.target.value))
}

function handleJump() {
  const parsed = Number(jumpInput.value)
  if (!Number.isInteger(parsed) || props.totalPages <= 0) {
    jumpInput.value = String(props.page + 1)
    return
  }
  const target = Math.min(Math.max(parsed, 1), props.totalPages)
  jumpInput.value = String(target)
  if (target - 1 !== props.page) {
    emit('update:page', target - 1)
  }
}
</script>

<template>
  <div class="pagination">
    <div class="pagination-size">
      <label for="paginationSize">每頁顯示</label>
      <select id="paginationSize" :value="size" @change="handleSizeChange">
        <option v-for="opt in sizeOptions" :key="opt" :value="opt">{{ opt }}</option>
      </select>
    </div>
    <div class="pagination-nav">
      <button class="btn btn-outline" type="button" :disabled="page <= 0" @click="goPrev">上一頁</button>
      <span class="pagination-status">第 {{ page + 1 }} 頁，共 {{ totalPages }} 頁</span>
      <button
        class="btn btn-outline"
        type="button"
        :disabled="page >= totalPages - 1 || totalPages === 0"
        @click="goNext"
      >
        下一頁
      </button>
      <div class="pagination-jump">
        <label for="paginationJump">跳至第</label>
        <input
          id="paginationJump"
          v-model="jumpInput"
          type="number"
          min="1"
          :max="totalPages || 1"
          :disabled="totalPages === 0"
          @keyup.enter="handleJump"
          @blur="handleJump"
        />
        <span>頁</span>
        <button
          class="btn btn-outline"
          type="button"
          :disabled="totalPages === 0"
          aria-label="跳頁"
          @click="handleJump"
        >
          →
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 1rem;
  margin-top: 1.25rem;
}

.pagination-size {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.pagination-size select {
  padding: 0.4rem 0.6rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font: inherit;
  color: var(--text-primary);
}

.pagination-nav {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.pagination-status {
  font-size: 0.85rem;
  color: var(--text-secondary);
  white-space: nowrap;
}

.pagination-jump {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.85rem;
  color: var(--text-secondary);
  white-space: nowrap;
}

.pagination-jump input {
  width: 3.5rem;
  padding: 0.35rem 0.5rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font: inherit;
  color: var(--text-primary);
  text-align: center;
}
</style>
