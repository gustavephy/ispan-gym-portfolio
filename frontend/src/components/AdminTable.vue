<template>
  <div class="admin-table card">
    <!-- 控制列：資料顯示數量與分頁頁碼 (Top) -->
    <div v-if="showControls" class="list-control-header bg-none" style="margin: 1rem 1rem 1.5rem 1rem; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 1rem;">
      <div style="display: flex; align-items: center; gap: 1.5rem; flex-wrap: wrap;">
        <span class="total-count-text">共 {{ totalItems }} 筆資料</span>
        <div class="page-size-selector">
          <label :for="'itemsPerPageSelect-' + id">顯示數量：</label>
          <select :id="'itemsPerPageSelect-' + id" :value="itemsPerPage" @change="$emit('update:itemsPerPage', Number($event.target.value))" class="select-sm">
            <option v-for="opt in sizeOptions" :key="opt" :value="opt">{{ opt }} 筆</option>
          </select>
        </div>
      </div>

      <!-- 分頁頁碼 (Top) -->
      <div class="pagination" v-if="totalPages > 1" style="margin: 0;">
        <button type="button" class="page-btn" :disabled="currentPage === 1" @click="$emit('update:currentPage', currentPage - 1)">‹</button>
        <template v-for="n in visiblePages" :key="n">
          <span v-if="n === '...'" class="page-ellipsis">...</span>
          <button v-else type="button" :class="['page-btn', { active: currentPage === n }]" @click="$emit('update:currentPage', n)">{{ n }}</button>
        </template>
        <button type="button" class="page-btn" :disabled="currentPage === totalPages" @click="$emit('update:currentPage', currentPage + 1)">›</button>
      </div>
    </div>

    <!-- 表格內容區 -->
    <div class="product-table-wrap">
      <table style="table-layout: fixed; width: 100%;">
        <thead>
          <tr>
            <th v-for="col in columns" :key="col.key" :style="{ width: col.width || 'auto', textAlign: col.align || 'center' }">
              {{ col.label }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in data" :key="row[rowKey] || index">
            <td v-for="col in columns" :key="col.key" :style="{ textAlign: col.align || 'center' }">
              <slot :name="col.key" :row="row" :value="row[col.key]">
                {{ row[col.key] }}
              </slot>
            </td>
          </tr>
          <tr v-if="data.length === 0">
            <td :colspan="columns.length" style="text-align: center; padding: 3rem; color: #94a3b8;">
              {{ emptyText }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 分頁頁碼 (Bottom) -->
    <div class="pagination" v-if="totalPages > 1" style="margin: 1rem 0;">
      <button type="button" class="page-btn" :disabled="currentPage === 1" @click="$emit('update:currentPage', currentPage - 1)">‹</button>
      <template v-for="n in visiblePages" :key="n">
        <span v-if="n === '...'" class="page-ellipsis">...</span>
        <button v-else type="button" :class="['page-btn', { active: currentPage === n }]" @click="$emit('update:currentPage', n)">{{ n }}</button>
      </template>
      <button type="button" class="page-btn" :disabled="currentPage === totalPages" @click="$emit('update:currentPage', currentPage + 1)">›</button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  id: { type: String, default: () => Math.random().toString(36).substring(2, 9) },
  data: { type: Array, required: true },
  columns: { type: Array, required: true },
  rowKey: { type: String, default: 'id' },
  showControls: { type: Boolean, default: true },
  totalItems: { type: Number, default: 0 },
  itemsPerPage: { type: Number, default: 10 },
  currentPage: { type: Number, default: 1 },
  sizeOptions: { type: Array, default: () => [5, 10, 20, 50] },
  emptyText: { type: String, default: '無符合篩選條件的資料' }
})

defineEmits(['update:currentPage', 'update:itemsPerPage'])

const totalPages = computed(() => Math.ceil(props.totalItems / props.itemsPerPage) || 1)

const visiblePages = computed(() => {
  const current = props.currentPage
  const total = totalPages.value
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => i + 1)
  }
  const pages = []
  pages.push(1)
  let start = Math.max(2, current - 2)
  let end = Math.min(total - 1, current + 2)
  if (current <= 4) {
    end = 5
  } else if (current >= total - 3) {
    start = total - 4
  }
  if (start > 2) {
    pages.push('...')
  }
  for (let i = start; i <= end; i++) {
    pages.push(i)
  }
  if (end < total - 1) {
    pages.push('...')
  }
  pages.push(total)
  return pages
})
</script>

<style scoped>
/* ===== 後台通用資料表格樣式 ===== */
.admin-table {
  border-radius: 16px;
  padding: 0;
  overflow: hidden;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.02);
  border: 1px solid #e2e8f0;
  background: #ffffff;
  margin-top: 1rem;
}
.admin-table table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed !important;
}
.admin-table table th {
  background: #f8fafc;
  color: #475569;
  font-weight: 700;
  padding: 0.5rem 0.75rem;
  text-align: center;
  border-bottom: 1px solid #e2e8f0;
  font-size: 0.78rem;
  letter-spacing: 0.3px;
  vertical-align: middle;
}
.admin-table table td {
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid #f1f5f9;
  color: #334155;
  font-size: 0.78rem;
  vertical-align: middle;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.admin-table table tbody tr {
  height: 90px;
}
.admin-table table tr:hover td {
  background: #f8fafc;
}

.product-table-wrap {
  overflow-x: auto;
}

:deep(.action-buttons-wrap) {
  display: flex !important;
  flex-direction: column !important;
  gap: 6px !important;
  justify-content: center !important;
  align-items: center !important;
  padding: 4px 0 !important;
}

:deep(.action-buttons-wrap .btn) {
  min-width: 64px !important;
  text-align: center !important;
  padding: 0.25rem 0.5rem !important;
  font-size: 0.8rem !important;
}

:deep(.product-thumb) {
  width: 70px !important;
  height: 70px !important;
  object-fit: cover !important;
  border-radius: 8px !important;
  border: 1px solid #e2e8f0 !important;
  margin: 0 auto !important;
}

/* ===== 分頁控制列與下拉選單樣式 ===== */
.list-control-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.25rem;
  padding: 0.5rem 1rem;
  background: var(--bg-section, #f8fafc);
  border-radius: var(--radius-md, 8px);
  border: 1px solid var(--border, #e2e8f0);
}
.list-control-header.bg-none {
  background: none;
  border: none;
  padding: 0;
  margin-bottom: 0;
}
.total-count-text {
  font-size: 0.88rem;
  color: var(--text-secondary, #64748b);
  font-weight: 600;
}
.page-size-selector {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.88rem;
  color: var(--text-secondary, #64748b);
}
.select-sm {
  padding: 0.3rem 1.5rem 0.3rem 0.6rem;
  font-size: 0.85rem;
  border-radius: 6px;
  border: 1px solid var(--border, #e2e8f0);
  background-color: var(--bg-card, #ffffff);
  color: var(--text-primary);
  cursor: pointer;
}

.pagination {
  display: flex;
  justify-content: center;
  gap: 0.5rem;
}

.page-btn {
  width: 28px !important;
  height: 28px !important;
  border: 1.5px solid var(--border, #e8e0d9);
  border-radius: 4px !important;
  background: var(--bg-section, #ffffff);
  color: var(--text-secondary, #6b5f58);
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.8rem !important;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
}

.page-btn.active,
.page-btn:hover {
  background: var(--primary, #5a4a42) !important;
  color: #ffffff !important;
  border-color: transparent !important;
}

.page-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  background: var(--bg-section, #ffffff) !important;
  color: var(--text-secondary, #6b5f58) !important;
  border: 1.5px solid var(--border, #e8e0d9) !important;
}
</style>
