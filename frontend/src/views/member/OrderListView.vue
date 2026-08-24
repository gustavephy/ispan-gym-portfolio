<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { getMyOrders, getOrderById, getOrderHistory, cancelOrder as apiCancelOrder } from '@/api/order.js'
import { useFeedback } from '@/composables/useFeedback.js'
import FeedbackToast from '@/components/FeedbackToast.vue'

const orders = ref([])
const loading = ref(false)
const expandedId = ref(null)
const detailLoading = ref(null) // orderId currently fetching detail
const orderItems = reactive({}) // orderId -> OrderItemResponse[]
const orderHistory = reactive({}) // orderId -> OrderStatusHistoryResponse[]

const statusMeta = {
  UNPAID: { label: '待付款', className: 'warning' },
  PAID: { label: '已付款', className: 'info' },
  SHIPPED: { label: '已出貨', className: 'info' },
  COMPLETED: { label: '已完成', className: 'success' },
  CANCELLED: { label: '已取消', className: 'muted' },
  FAILED: { label: '付款失敗', className: 'danger' },
  REFUNDED: { label: '已退款', className: 'muted' },
}

// 狀態時間軸的事件說明；note 是後端寫死的短代碼（見 OrderService／EcpayService）
const historyNoteMeta = {
  CHECKOUT: '建立訂單',
  ECPAY_NOTIFY: '綠界付款結果通知',
  CANCEL: '取消訂單',
  REFUND_REQUEST: '申請退款',
  REFUND_COMPLETE: '退款完成',
  SHIP: '出貨',
  COMPLETE: '訂單完成',
}

const orderCategories = [
  { key: 'ALL', label: '全部訂單', statuses: null },
  { key: 'UNPAID', label: '待付款', statuses: ['UNPAID'] },
  { key: 'FAILED', label: '取消/退款', statuses: ['CANCELLED', 'FAILED', 'REFUNDED'] },
  { key: 'SUCCESS', label: '已完成訂單', statuses: ['PAID', 'SHIPPED', 'COMPLETED'] },
]
const activeCategory = ref(orderCategories[0].key)

function ordersInCategory(category) {
  if (!category.statuses) return orders.value
  return orders.value.filter((order) => category.statuses.includes(order.status))
}

const filteredOrders = computed(() => {
  const category = orderCategories.find((c) => c.key === activeCategory.value)
  return ordersInCategory(category)
})

function categoryCount(category) {
  return ordersInCategory(category).length
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
      const [detailRes, historyRes] = await Promise.all([
        getOrderById(order.orderId),
        getOrderHistory(order.orderId),
      ])
      orderItems[order.orderId] = detailRes.data.items
      orderHistory[order.orderId] = historyRes.data
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
    // 每次確認送出都用新的一次性金鑰，同一次確認若被重複觸發（雙擊、網路重送）只會真正執行一次
    const res = await apiCancelOrder(order.orderId, {}, crypto.randomUUID())
    order.status = res.data.status
    order.totalAmount = res.data.totalAmount
    if (orderItems[order.orderId]) orderItems[order.orderId] = res.data.items
    const historyRes = await getOrderHistory(order.orderId)
    orderHistory[order.orderId] = historyRes.data
    showFeedback(isRefund ? '退款申請已送出' : '訂單已取消')
  } catch (err) {
    // 攔截器已正規化錯誤，訊息在 err.message；err.response 不存在（見 normalizeApiError）
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

    <template v-else>
      <div class="order-tabs">
        <button
          v-for="category in orderCategories"
          :key="category.key"
          class="order-tab"
          :class="{ active: activeCategory === category.key }"
          @click="activeCategory = category.key"
        >
          {{ category.label }}
          <span class="tab-count">{{ categoryCount(category) }}</span>
        </button>
      </div>

      <div v-if="filteredOrders.length === 0" class="empty-state">
        <p>此分類目前沒有訂單</p>
      </div>

      <div v-else class="order-list">
        <div v-for="order in filteredOrders" :key="order.orderId" class="order-card">
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

              <div class="order-timeline" v-if="orderHistory[order.orderId]?.length">
                <h4 class="timeline-title">訂單狀態時間軸</h4>
                <ul class="timeline-list">
                  <li v-for="(event, idx) in orderHistory[order.orderId]" :key="idx" class="timeline-item">
                    <span class="timeline-dot" :class="statusMeta[event.toStatus]?.className"></span>
                    <div class="timeline-body">
                      <div class="timeline-row">
                        <span class="timeline-note">{{ historyNoteMeta[event.note] || event.note }}</span>
                        <span class="status-badge timeline-badge" :class="statusMeta[event.toStatus]?.className">
                          {{ statusMeta[event.toStatus]?.label || event.toStatus }}
                        </span>
                      </div>
                      <span class="timeline-time">{{ formatDate(event.changedAt) }}</span>
                    </div>
                  </li>
                </ul>
              </div>
            </template>
          </div>
        </div>
      </div>
    </template>

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
.order-tabs {
  display: flex;
  gap: 0.5rem;
  margin-top: 2rem;
  border-bottom: 1px solid var(--border);
}
.order-tab {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.7rem 1rem;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-muted);
  cursor: pointer;
}
.order-tab.active {
  color: var(--accent-hover);
  border-bottom-color: var(--accent-hover);
}
.tab-count {
  padding: 0.05rem 0.5rem;
  border-radius: 999px;
  background: var(--bg-soft);
  color: var(--text-secondary);
  font-size: 0.75rem;
  font-weight: 700;
}
.order-tab.active .tab-count {
  background: var(--accent-hover);
  color: #fff;
}
.order-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  margin-top: 1.25rem;
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
.order-timeline {
  margin-top: 1rem;
  padding-top: 1rem;
  border-top: 1px dashed var(--border);
}
.timeline-title {
  font-size: 0.8rem;
  font-weight: 700;
  color: var(--text-muted);
  margin-bottom: 0.75rem;
}
.timeline-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.timeline-item {
  display: flex;
  gap: 0.65rem;
  padding-bottom: 0.9rem;
  position: relative;
}
.timeline-item:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 4px;
  top: 12px;
  bottom: -2px;
  width: 1px;
  background: var(--border);
}
.timeline-dot {
  flex-shrink: 0;
  width: 9px;
  height: 9px;
  margin-top: 4px;
  border-radius: 50%;
  background: var(--text-muted);
}
.timeline-dot.warning { background: #a06400; }
.timeline-dot.info { background: #1d4ed8; }
.timeline-dot.success { background: #157347; }
.timeline-dot.danger { background: #c92a2a; }
.timeline-body {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}
.timeline-row {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}
.timeline-note {
  font-size: 0.85rem;
  color: var(--text-primary);
}
.timeline-badge {
  font-size: 0.7rem;
  padding: 0.15rem 0.55rem;
}
.timeline-time {
  font-size: 0.75rem;
  color: var(--text-muted);
}
@media (max-width: 640px) {
  .order-header { flex-direction: column; align-items: flex-start; }
  .order-header-side { width: 100%; justify-content: space-between; }
}
</style>
