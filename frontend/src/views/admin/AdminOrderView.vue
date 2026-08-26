<script setup>
import { ref, reactive, onMounted } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import {
  searchOrders,
  getAdminOrderDetail,
  shipOrder as apiShipOrder,
  completeOrder as apiCompleteOrder,
  simulateOrderPayment as apiSimulatePayment,
  getPendingRefunds,
} from '@/api/adminOrder.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import { useFeedback } from '@/composables/useFeedback.js'
import FeedbackToast from '@/components/FeedbackToast.vue'
import OperationMessage from '@/components/OperationMessage.vue'
import Pagination from '@/components/Pagination.vue'

const route = useRoute()

const statusMeta = {
  UNPAID: { label: '待付款', className: 'warning' },
  PAID: { label: '已付款', className: 'info' },
  SHIPPED: { label: '已出貨', className: 'info' },
  COMPLETED: { label: '已完成', className: 'success' },
  CANCELLED: { label: '已取消', className: 'muted' },
  REFUNDED: { label: '已退款', className: 'muted' },
  FAILED: { label: '付款失敗', className: 'danger' },
}
const historyNoteMeta = {
  CHECKOUT: '建立訂單',
  ECPAY_NOTIFY: '綠界付款結果通知',
  SIMULATE_PAYMENT: '模擬付款（測試）',
  CANCEL: '取消訂單',
  REFUND_REQUEST: '申請退款',
  REFUND_COMPLETE: '退款完成',
  REFUND_REJECT: '退款申請遭駁回',
  SHIP: '出貨',
  COMPLETE: '訂單完成',
}

const filters = reactive({
  status: '',
  keyword: '',
  createdFromDate: '',
  createdToDate: '',
})

const orders = ref([])
const loading = ref(true)
const listMessage = ref('')
const page = ref(0)
const size = ref(20)
const totalPages = ref(0)

const expandedId = ref(null)
const detail = reactive({}) // orderId -> AdminOrderDetailResponse
const detailLoading = ref(null)
const actingId = ref(null) // orderId 正在執行出貨/完成中

// orderId -> PendingRefundResponse；用來在訂單卡片標示「這筆有退款申請在等待處理」，
// 跟退款處理頁互通，資料沿用既有的 getPendingRefunds()，不需要新增後端 API
const pendingRefundsByOrder = reactive({})

const { feedback, showFeedback } = useFeedback()

function formatDate(isoString) {
  return isoString ? new Date(isoString).toLocaleString('zh-TW', { hour12: false }) : '—'
}

function formatMoney(amount) {
  return `NT$ ${Number(amount).toLocaleString()}`
}

// 私教課品項的補充說明：共 N 堂、負責教練
function courseItemMeta(item) {
  const parts = []
  if (item.totalSessions != null) parts.push(`共 ${item.totalSessions} 堂`)
  if (item.trainerName) parts.push(`教練 ${item.trainerName}`)
  return parts.join('・')
}

function searchParams() {
  return {
    status: filters.status,
    keyword: filters.keyword,
    createdFrom: filters.createdFromDate ? `${filters.createdFromDate}T00:00:00` : undefined,
    createdTo: filters.createdToDate ? `${filters.createdToDate}T23:59:59` : undefined,
    page: page.value,
    size: size.value,
  }
}

async function fetchOrders() {
  loading.value = true
  listMessage.value = ''
  try {
    const res = await searchOrders(searchParams())
    orders.value = res.data.content
    totalPages.value = res.data.totalPages
  } catch (err) {
    if (isInlineError(err)) {
      listMessage.value = err.message
    } else {
      notifyApiError(err)
    }
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 0
  fetchOrders()
}

function handlePageChange(newPage) {
  page.value = newPage
  fetchOrders()
}

function handleSizeChange(newSize) {
  size.value = newSize
  page.value = 0
  fetchOrders()
}

async function fetchPendingRefunds() {
  try {
    const res = await getPendingRefunds()
    for (const key of Object.keys(pendingRefundsByOrder)) delete pendingRefundsByOrder[key]
    for (const refund of res.data) pendingRefundsByOrder[refund.orderId] = refund
  } catch {
    // 只是輔助提示，安靜失敗即可，不影響訂單搜尋本身
  }
}

async function toggleExpand(order) {
  if (expandedId.value === order.orderId) {
    expandedId.value = null
    return
  }
  expandedId.value = order.orderId
  detailLoading.value = order.orderId
  try {
    const res = await getAdminOrderDetail(order.orderId)
    detail[order.orderId] = res.data
  } finally {
    detailLoading.value = null
  }
}

async function refreshRow(orderId, updated) {
  detail[orderId] = updated
  const row = orders.value.find((o) => o.orderId === orderId)
  if (row) row.status = updated.status
  const res = await getAdminOrderDetail(orderId)
  detail[orderId] = res.data
}

async function handleShip(order) {
  actingId.value = order.orderId
  try {
    const res = await apiShipOrder(order.orderId)
    await refreshRow(order.orderId, res.data)
    showFeedback('已標記為出貨')
  } catch (err) {
    showFeedback(err.message || '操作失敗，請稍後再試', 'error')
  } finally {
    actingId.value = null
  }
}

async function handleComplete(order) {
  actingId.value = order.orderId
  try {
    const res = await apiCompleteOrder(order.orderId)
    await refreshRow(order.orderId, res.data)
    showFeedback('已標記為完成')
  } catch (err) {
    showFeedback(err.message || '操作失敗，請稍後再試', 'error')
  } finally {
    actingId.value = null
  }
}

async function handleSimulatePayment(order) {
  if (!window.confirm(`確定要模擬訂單 ${order.merchantTradeNo} 付款成功嗎？將走與綠界付款相同的履約流程（本機展示用）。`)) return
  actingId.value = order.orderId
  try {
    const res = await apiSimulatePayment(order.orderId)
    await refreshRow(order.orderId, res.data)
    showFeedback('已模擬付款成功，訂單已轉為已付款')
  } catch (err) {
    showFeedback(err.message || '操作失敗，請稍後再試', 'error')
  } finally {
    actingId.value = null
  }
}

onMounted(async () => {
  // 從退款處理頁點「查看訂單」跳過來時，網址會帶 ?keyword=merchantTradeNo，
  // 直接代入搜尋框並送出查詢；查到唯一一筆時順便展開，省得使用者再點一次
  if (route.query.keyword) {
    filters.keyword = String(route.query.keyword)
  }
  await Promise.all([fetchOrders(), fetchPendingRefunds()])
  if (route.query.keyword && orders.value.length === 1) {
    toggleExpand(orders.value[0])
  }
})
</script>

<template>
  <div class="page-container">
    <span class="section-tag">ADMIN</span>
    <h2 class="section-title">訂單管理</h2>
    <p class="section-desc">搜尋、查看所有會員訂單，並可為已付款的實體商品訂單標記出貨/完成。</p>

    <div class="filter-bar">
      <div class="filter-field">
        <label>狀態</label>
        <select v-model="filters.status">
          <option value="">全部</option>
          <option v-for="(meta, key) in statusMeta" :key="key" :value="key">{{ meta.label }}</option>
        </select>
      </div>
      <div class="filter-field">
        <label>關鍵字</label>
        <input v-model="filters.keyword" placeholder="訂單編號 / 收件人 / 會員信箱" @keyup.enter="handleSearch" />
      </div>
      <div class="filter-field">
        <label>下單日期（起）</label>
        <input v-model="filters.createdFromDate" type="date" />
      </div>
      <div class="filter-field">
        <label>下單日期（迄）</label>
        <input v-model="filters.createdToDate" type="date" />
      </div>
      <button class="btn btn-accent" type="button" @click="handleSearch">搜尋</button>
    </div>

    <OperationMessage :message="listMessage" />

    <div v-if="loading" class="spinner"></div>

    <template v-else>
      <p v-if="orders.length === 0" class="empty-state">查無符合條件的訂單。</p>

      <div v-else class="order-list">
        <div v-for="order in orders" :key="order.orderId" class="order-card">
          <button class="order-header" @click="toggleExpand(order)">
            <div class="order-header-main">
              <span class="order-title">{{ order.title || order.merchantTradeNo }}</span>
              <span class="order-no mono">{{ order.merchantTradeNo }}</span>
              <span class="order-member">{{ order.memberName }}（{{ order.memberEmail }}）</span>
              <span class="order-date">下單 {{ formatDate(order.createdAt) }}</span>
            </div>
            <div class="order-header-side">
              <span class="status-badge" :class="statusMeta[order.status]?.className">
                {{ statusMeta[order.status]?.label || order.status }}
              </span>
              <span v-if="pendingRefundsByOrder[order.orderId]" class="status-badge warning">待退款</span>
              <span class="order-amount">NT$ {{ Number(order.totalAmount).toLocaleString() }}</span>
              <span class="expand-icon">{{ expandedId === order.orderId ? '▲' : '▼' }}</span>
            </div>
          </button>

          <div v-if="expandedId === order.orderId" class="order-detail">
            <div v-if="detailLoading === order.orderId" class="detail-loading">載入中...</div>
            <template v-else-if="detail[order.orderId]">
              <div class="detail-section">
                <h4 class="detail-title">收件資訊</h4>
                <p class="detail-text">{{ detail[order.orderId].receiverName || '未填寫' }}　{{ detail[order.orderId].receiverPhone || '' }}</p>
                <p class="detail-text">{{ detail[order.orderId].receiverAddress || '（無實體地址，如課程/訂閱訂單）' }}</p>
              </div>

              <div class="detail-section">
                <h4 class="detail-title">訂單品項</h4>

                <div v-if="detail[order.orderId].subscription" class="detail-item-row">
                  <div class="detail-item-info">
                    <span class="detail-item-name">{{ detail[order.orderId].subscription.name }}</span>
                    <span class="detail-item-meta">
                      會員訂閱方案・{{ detail[order.orderId].subscription.durationDays }} 天使用期
                    </span>
                    <span
                      v-if="detail[order.orderId].subscription.description"
                      class="detail-item-meta"
                    >{{ detail[order.orderId].subscription.description }}</span>
                  </div>
                  <span>{{ formatMoney(detail[order.orderId].subscription.price) }}</span>
                </div>

                <div v-for="item in detail[order.orderId].items" :key="item.orderItemId" class="detail-item-row">
                  <div class="detail-item-info">
                    <span class="detail-item-name">{{ item.productNameAtBuy }} × {{ item.quantity }}</span>
                    <span v-if="item.itemType === 'COURSE'" class="detail-item-meta">
                      私教課程<template v-if="courseItemMeta(item)">・{{ courseItemMeta(item) }}</template>
                    </span>
                    <span v-if="item.quantity > 1" class="detail-item-meta">單價 {{ formatMoney(item.priceAtBuy) }}</span>
                  </div>
                  <span>{{ formatMoney(item.lineAmount) }}</span>
                </div>

                <div
                  v-if="!detail[order.orderId].subscription && detail[order.orderId].items.length === 0"
                  class="detail-text"
                >（無品項資料）</div>
              </div>

              <div v-if="pendingRefundsByOrder[order.orderId]" class="refund-callout">
                <p class="refund-callout-title">這筆訂單有一筆退款申請正在等待處理</p>
                <p class="refund-callout-text">
                  退款單號 {{ pendingRefundsByOrder[order.orderId].refundNo }}．
                  金額 NT$ {{ Number(pendingRefundsByOrder[order.orderId].amount).toLocaleString() }}．
                  {{ formatDate(pendingRefundsByOrder[order.orderId].requestedAt) }} 申請
                </p>
                <RouterLink
                  :to="`/admin/refunds?highlight=${pendingRefundsByOrder[order.orderId].refundId}`"
                  class="btn btn-outline"
                >前往退款處理 →</RouterLink>
              </div>

              <div class="detail-actions">
                <button
                  v-if="detail[order.orderId].status === 'UNPAID'"
                  class="btn btn-accent"
                  :disabled="actingId === order.orderId"
                  title="本機沒有對外 URL、綠界通知連不進來時，用這個把訂單推進到已付款"
                  @click="handleSimulatePayment(order)"
                >{{ actingId === order.orderId ? '處理中…' : '模擬付款成功' }}</button>
                <button
                  v-if="detail[order.orderId].status === 'PAID'"
                  class="btn btn-outline"
                  :disabled="actingId === order.orderId || !!pendingRefundsByOrder[order.orderId]"
                  :title="pendingRefundsByOrder[order.orderId] ? '這筆訂單有退款申請正在處理中，需先處理完退款' : ''"
                  @click="handleShip(order)"
                >{{ actingId === order.orderId ? '處理中…' : '標記已出貨' }}</button>
                <button
                  v-if="['PAID', 'SHIPPED'].includes(detail[order.orderId].status)"
                  class="btn btn-accent"
                  :disabled="actingId === order.orderId || !!pendingRefundsByOrder[order.orderId]"
                  :title="pendingRefundsByOrder[order.orderId] ? '這筆訂單有退款申請正在處理中，需先處理完退款' : ''"
                  @click="handleComplete(order)"
                >{{ actingId === order.orderId ? '處理中…' : '標記完成' }}</button>
              </div>

              <div class="order-timeline" v-if="detail[order.orderId].history?.length">
                <h4 class="detail-title">狀態時間軸</h4>
                <ul class="timeline-list">
                  <li v-for="(event, idx) in detail[order.orderId].history" :key="idx" class="timeline-item">
                    <span class="timeline-dot" :class="statusMeta[event.toStatus]?.className"></span>
                    <div class="timeline-body">
                      <div class="timeline-row">
                        <span class="timeline-note">{{ historyNoteMeta[event.note] || event.note }}</span>
                        <span class="status-badge timeline-badge" :class="statusMeta[event.toStatus]?.className">
                          {{ statusMeta[event.toStatus]?.label || event.toStatus }}
                        </span>
                        <span class="timeline-source">{{ event.changeSource }}</span>
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

      <Pagination
        :page="page"
        :total-pages="totalPages"
        :size="size"
        :size-options="[10, 20, 50]"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      />
    </template>

    <FeedbackToast :feedback="feedback" />
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 1rem;
  margin: 1.5rem 0;
  padding: 1.1rem 1.25rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-section);
}
.filter-field {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  font-size: 0.82rem;
  color: var(--text-secondary);
}
.filter-field input,
.filter-field select {
  padding: 0.5rem 0.7rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font: inherit;
  color: var(--text-primary);
  min-width: 160px;
}
.empty-state {
  color: var(--text-muted);
  padding: 2rem 0;
}
.order-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
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
.order-title {
  font-size: 0.95rem;
  font-weight: 700;
  color: var(--text-primary);
  overflow-wrap: anywhere;
}
.order-header .mono {
  font-size: 0.78rem;
  color: var(--text-muted);
}
.order-member {
  font-size: 0.82rem;
  color: var(--text-secondary);
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
.detail-section {
  margin-bottom: 1rem;
}
.detail-title {
  font-size: 0.8rem;
  font-weight: 700;
  color: var(--text-muted);
  margin-bottom: 0.5rem;
}
.detail-text {
  font-size: 0.85rem;
  color: var(--text-secondary);
  margin: 0.15rem 0;
}
.detail-item-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  font-size: 0.88rem;
  color: var(--text-secondary);
  padding: 0.4rem 0;
}
.detail-item-info {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  min-width: 0;
}
.detail-item-name {
  font-weight: 600;
  color: var(--text-primary);
}
.detail-item-meta {
  font-size: 0.78rem;
  color: var(--text-muted);
}
.detail-item-row > span {
  flex-shrink: 0;
  white-space: nowrap;
}
.detail-actions {
  display: flex;
  gap: 0.6rem;
  margin: 1rem 0;
  padding-top: 0.75rem;
  border-top: 1px dashed var(--border);
}
.refund-callout {
  margin-top: 1rem;
  padding: 0.85rem 1rem;
  border-radius: var(--radius-sm);
  background: #fff3cd;
  border: 1px dashed #d8ae3f;
}
.refund-callout-title {
  font-size: 0.85rem;
  font-weight: 700;
  color: #a06400;
  margin-bottom: 0.35rem;
}
.refund-callout-text {
  font-size: 0.8rem;
  color: var(--text-secondary);
  margin-bottom: 0.7rem;
}
.order-timeline {
  margin-top: 1rem;
  padding-top: 1rem;
  border-top: 1px dashed var(--border);
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
.timeline-source {
  font-size: 0.72rem;
  color: var(--text-muted);
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
