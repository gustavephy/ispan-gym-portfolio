<script setup>
import { ref, onMounted } from 'vue'
import { getMyQuotas } from '@/api/memberCourseQuota.js'
import { notifyApiError } from '@/api/error-bus.js'
import Pagination from '@/components/Pagination.vue'

// 額度狀態對照表：後端回傳 ACTIVE/DEPLETED/EXPIRED（見 MemberCourseQuota.getStatus()），
// 這裡只負責轉成中文與樣式，不做篩選——這個頁面刻意全部顯示，讓會員自己看得到
// 用完/過期的紀錄，不隱藏歷史
const STATUS_MAP = {
  ACTIVE: { label: '使用中', className: 'status-active' },
  DEPLETED: { label: '已用完', className: 'status-depleted' },
  EXPIRED: { label: '已過期', className: 'status-expired' },
}

const quotas = ref([])
const loading = ref(false)
const page = ref(0)
const size = ref(10)
const totalPages = ref(0)

async function fetchQuotas() {
  loading.value = true
  try {
    const { data } = await getMyQuotas({ page: page.value, size: size.value })
    quotas.value = data.items
    totalPages.value = data.totalPages
  } catch (e) {
    notifyApiError(e)
  } finally {
    loading.value = false
  }
}

function handlePageChange(newPage) {
  page.value = newPage
  fetchQuotas()
}

function formatDate(value) {
  if (!value) return '無期限'
  const d = new Date(value)
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`
}

onMounted(fetchQuotas)
</script>

<template>
  <div class="my-quota-page">
    <h1 class="page-title">我的課程額度</h1>

    <div v-if="loading" class="spinner"></div>

    <div v-else-if="quotas.length === 0" class="empty-state">
      目前沒有任何私教課額度紀錄
    </div>

    <div v-else class="quota-list">
      <div v-for="q in quotas" :key="q.id" class="quota-card">
        <div class="quota-card__header">
          <h3>{{ q.courseName }}</h3>
          <span class="status-badge" :class="STATUS_MAP[q.status]?.className">
            {{ STATUS_MAP[q.status]?.label ?? q.status }}
          </span>
        </div>
        <p class="quota-detail">教練：{{ q.trainerName }}</p>
        <div class="quota-numbers">
          <div class="quota-number">
            <span class="quota-number__value">{{ q.remainingCourse }}</span>
            <span class="quota-number__label">剩餘</span>
          </div>
          <div class="quota-number">
            <span class="quota-number__value">{{ q.reservedCourse }}</span>
            <span class="quota-number__label">預約中</span>
          </div>
          <div class="quota-number">
            <span class="quota-number__value">{{ q.usedCourse }}</span>
            <span class="quota-number__label">已使用</span>
          </div>
          <div class="quota-number">
            <span class="quota-number__value">{{ q.total }}</span>
            <span class="quota-number__label">總堂數</span>
          </div>
        </div>
        <p class="quota-detail">到期日：{{ formatDate(q.expiryDate) }}</p>
      </div>
    </div>

    <Pagination
      v-if="totalPages > 0"
      :page="page"
      :size="size"
      :total-pages="totalPages"
      @update:page="handlePageChange"
    />
  </div>
</template>

<style scoped>
.my-quota-page {
  max-width: 900px;
  margin: 0 auto;
  padding: 2rem 1rem;
}
.page-title {
  margin-bottom: 1.5rem;
}
.quota-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.quota-card {
  padding: 1.25rem;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}
.quota-card__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.5rem;
}
.quota-detail {
  color: var(--text-secondary, #666);
  margin: 0.25rem 0;
}
.quota-numbers {
  display: flex;
  gap: 1.5rem;
  margin: 0.75rem 0;
}
.quota-number {
  display: flex;
  flex-direction: column;
  align-items: center;
}
.quota-number__value {
  font-size: 1.5rem;
  font-weight: 700;
}
.quota-number__label {
  font-size: 0.8rem;
  color: var(--text-secondary, #666);
}
.status-badge {
  padding: 0.25rem 0.75rem;
  border-radius: 999px;
  font-size: 0.85rem;
}
.status-active {
  background: #e6f4ea;
  color: #1e7e34;
}
.status-depleted {
  background: #eee;
  color: #666;
}
.status-expired {
  background: #fdecea;
  color: #c0392b;
}
.empty-state {
  text-align: center;
  padding: 3rem 0;
  color: var(--text-secondary, #666);
}
</style>
