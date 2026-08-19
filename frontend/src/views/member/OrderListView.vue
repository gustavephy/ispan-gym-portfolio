<script setup>
import { ref, reactive, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { getMyOrders, getOrderById, cancelOrder as apiCancelOrder } from '@/api/order.js'
import { useFeedback } from '@/composables/useFeedback.js'
import FeedbackToast from '@/components/FeedbackToast.vue'

const orders = ref([])
const loading = ref(false)
const expandedId = ref(null)
const detailLoading = ref(null) // orderId currently fetching detail
const orderItems = reactive({}) // orderId -> OrderItemResponse[]

const statusMeta = {
  UNPAID: { label: '未付款', className: 'warning' },
  PAID: { label: '已付款', className: 'info' },
  SHIPPED: { label: '已出貨', className: 'info' },
  COMPLETED: { label: '已完成', className: 'success' },
  CANCELLED: { label: '已取消', className: 'muted' },
  FAILED: { label: '付款失敗', className: 'danger' },
  REFUNDED: { label: '已退款', className: 'muted' },
}

const { feedback, showFeedback } = useFeedback()

function formatDate(isoString) {
  return new Date(isoString).toLocaleString('zh-TW', { hour12: false })
}

async function loadOrders() {
  loading.value = true
  try {
    const res = await getMyOrders()
    orders.value = res.data
  } finally {
    loading.value = false
  }
}

async function toggleExpand(order) {
  if (expandedId.value === order.orderId) {
    expandedId.value = null
    return
  }
  expandedId.value = order.orderId
  if (!orderItems[order.orderId]) {
    detailLoading.value = order.orderId
    try {
      const res = await getOrderById(order.orderId)
      orderItems[order.orderId] = res.data.items
    } finally {
      detailLoading.value = null
    }
  }
}

async function handleCancelOrRefund(order) {
  const isRefund = order.status === 'PAID'
  const confirmed = window.confirm(
    isRefund
      ? '確定要針對這筆已付款訂單申請退款嗎？後續將由客服審核處理。'
      : '確定要取消這筆未付款訂單嗎？'
  )
  if (!confirmed) return

  try {
    const res = await apiCancelOrder(order.orderId, {})
    order.status = res.data.status
    order.totalAmount = res.data.totalAmount
    if (orderItems[order.orderId]) orderItems[order.orderId] = res.data.items
    showFeedback(isRefund ? '退款申請已送出' : '訂單已取消')
  } catch (err) {
    showFeedback(err.message || '操作失敗，請稍後再試', 'error')
  }
}

onMounted(loadOrders)
</script>

<template>
  <div class="page-container">
    <h1 class="section-title">我的訂單</h1>

    <div v-if="loading" class="spinner"></div>

    <div v-else-if="orders.length === 0" class="empty-state">
      <p>目前沒有任何訂單</p>
      <RouterLink to="/products" class="btn btn-accent">去逛逛</RouterLink>
    </div>

    <div v-else class="order-list">
      <div v-for="order in orders" :key="order.orderId" class="order-card">
        <button class="order-header" @click="toggleExpand(order)">
          <div class="order-header-main">
            <span class="order-no mono">{{ order.merchantTradeNo }}</span>
            <span class="order-date">{{ formatDate(order.createdAt) }}</span>
          </div>
          <div class="order-header-side">
            <span class="status-badge" :class="statusMeta[order.status]?.className">
              {{ statusMeta[order.status]?.label || order.status }}
            </span>
            <span class="order-amount">NT$ {{ Number(order.totalAmount).toLocaleString() }}</span>
            <span class="expand-icon">{{ expandedId === order.orderId ? '▲' : '▼' }}</span>
          </div>
        </button>

        <div v-if="expandedId === order.orderId" class="order-detail">
          <div v-if="detailLoading === order.orderId" class="detail-loading">載入中...</div>
          <template v-else>
            <div v-for="item in orderItems[order.orderId]" :key="item.orderItemId" class="detail-item-row">
              <span>{{ item.productNameAtBuy }} × {{ item.quantity }}</span>
              <span>NT$ {{ Number(item.lineAmount).toLocaleString() }}</span>
            </div>

            <div class="detail-actions" v-if="order.status === 'UNPAID' || order.status === 'PAID'">
              <button class="btn btn-outline cancel-btn" @click="handleCancelOrRefund(order)">
                {{ order.status === 'PAID' ? '申請退款' : '取消訂單' }}
              </button>
            </div>
          </template>
        </div>
      </div>
    </div>

    <FeedbackToast :feedback="feedback" />
  </div>
</template>

<style scoped>
.empty-state {
  text-align: center;
  padding: 4rem 2rem;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  color: var(--text-muted);
}
.order-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  margin-top: 2rem;
}
.order-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-section);
  overflow: hidden;
}
.order-header {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 1rem 1.25rem;
  background: none;
  border: none;
  text-align: left;
  flex-wrap: wrap;
}
.order-header-main {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.mono {
  font-family: ui-monospace, "SF Mono", "Cascadia Code", Consolas, monospace;
  font-size: 0.85rem;
}
.order-date {
  font-size: 0.78rem;
  color: var(--text-muted);
}
.order-header-side {
  display: flex;
  align-items: center;
  gap: 1rem;
}
.status-badge {
  padding: 0.25rem 0.7rem;
  border-radius: 999px;
  font-size: 0.78rem;
  font-weight: 700;
}
.status-badge.warning { background: #fff3cd; color: #a06400; }
.status-badge.info { background: #dbeafe; color: #1d4ed8; }
.status-badge.success { background: #d4f4dd; color: #157347; }
.status-badge.danger { background: #fde2e2; color: #c92a2a; }
.status-badge.muted { background: var(--bg-soft); color: var(--text-muted); }
.order-amount {
  font-weight: 700;
  color: var(--accent-hover);
}
.expand-icon {
  font-size: 0.7rem;
  color: var(--text-muted);
}
.order-detail {
  border-top: 1px solid var(--border);
  padding: 1rem 1.25rem;
  background: var(--bg-soft);
}
.detail-loading {
  font-size: 0.85rem;
  color: var(--text-muted);
}
.detail-item-row {
  display: flex;
  justify-content: space-between;
  font-size: 0.88rem;
  color: var(--text-secondary);
  padding: 0.3rem 0;
}
.detail-actions {
  margin-top: 0.75rem;
  padding-top: 0.75rem;
  border-top: 1px dashed var(--border);
  display: flex;
  justify-content: flex-end;
}
.cancel-btn {
  font-size: 0.85rem;
  padding: 0.45rem 1.1rem;
}
@media (max-width: 640px) {
  .order-header { flex-direction: column; align-items: flex-start; }
  .order-header-side { width: 100%; justify-content: space-between; }
}
</style>
