<template>
  <div v-if="totalElements > 0" class="app-pagination-container">
    <!-- 左側：總筆數資訊 -->
    <div class="pagination-info">
      共 {{ totalElements }} 筆
    </div>

    <!-- 中間：使用全域樣式的分頁按鈕 -->
    <div class="pagination">
      <button 
        class="page-btn" 
        :disabled="modelValue === 1" 
        @click="changePage(modelValue - 1)"
      >
        ‹
      </button>
      <button 
        v-for="p in totalPages" 
        :key="p"
        class="page-btn"
        :class="{ active: modelValue === p }"
        @click="changePage(p)"
      >
        {{ p }}
      </button>
      <button 
        class="page-btn" 
        :disabled="modelValue === totalPages" 
        @click="changePage(modelValue + 1)"
      >
        ›
      </button>
    </div>

    <!-- 右側：每頁顯示筆數選擇器 -->
    <div v-if="showSizeSelector" class="page-size-row">
      <label>每頁顯示：</label>
      <select 
        :value="pageSize" 
        class="select-sm" 
        @change="changePageSize($event.target.value)"
      >
        <option v-for="size in sizeOptions" :key="size" :value="size">
          {{ size }} 筆
        </option>
      </select>
    </div>
  </div>
</template>

<script setup>
import { defineProps, defineEmits } from 'vue'

const props = defineProps({
  modelValue: {
    type: Number,
    required: true
  },
  totalPages: {
    type: Number,
    required: true
  },
  totalElements: {
    type: Number,
    required: true
  },
  pageSize: {
    type: Number,
    default: 5
  },
  showSizeSelector: {
    type: Boolean,
    default: false
  },
  sizeOptions: {
    type: Array,
    default: () => [5, 10, 20]
  }
})

const emit = defineEmits(['update:modelValue', 'update:pageSize', 'change'])

function changePage(page) {
  if (page < 1 || page > props.totalPages) return
  emit('update:modelValue', page)
  emit('change', { page, pageSize: props.pageSize })
}

function changePageSize(val) {
  const size = Number(val)
  emit('update:pageSize', size)
  emit('change', { page: 1, pageSize: size })
}
</script>

<style scoped>
.app-pagination-container {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 1.5rem;
  margin-top: 2rem;
  margin-bottom: 2rem;
  width: 100%;
}

.pagination-info {
  font-size: 0.88rem;
  font-weight: 600;
  color: var(--text-secondary);
}

.page-size-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.88rem;
  font-weight: 600;
  color: var(--text-secondary);
}

.select-sm {
  border: 1.5px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 0.3rem 1.5rem 0.3rem 0.5rem;
  font-size: 0.85rem;
  background-color: var(--bg-section);
  color: var(--text-secondary);
  outline: none;
  cursor: pointer;
  min-width: 85px;
  transition: var(--transition);
}

.select-sm:focus {
  border-color: var(--primary);
}

/* 復刻全域樣式微調，確保按鈕排版在一行 */
.pagination {
  margin-top: 0 !important;
  display: flex;
  gap: 0.5rem;
}

.page-btn {
  width: 38px;
  height: 38px;
  border: 1.5px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-section);
  color: var(--text-secondary);
  cursor: pointer;
  transition: var(--transition);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.9rem;
}

.page-btn.active,
.page-btn:hover {
  background: var(--primary);
  color: #fff;
  border-color: transparent;
}

/* 覆蓋 disabled 狀態以防止與 active/hover 衝突 */
.page-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
  background: var(--bg-section) !important;
  color: var(--text-secondary) !important;
  border-color: var(--border) !important;
}
</style>
