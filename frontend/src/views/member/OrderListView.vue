<script setup>
defineOptions({ name: 'OrderListView' })
import { ref, reactive, computed, watch, onMounted, onUnmounted, onActivated, nextTick } from 'vue'
import { RouterLink, useRouter, useRoute } from 'vue-router'
import {
  getMyOrders,
  getOrderById,
  getOrderHistory,
  cancelOrder as apiCancelOrder,
  syncOrderPayment,
} from '@/api/order.js'
import { useEcpayCheckout } from '@/composables/useEcpayCheckout.js'
import { useCartStore } from '@/stores/cart.js'
import { useFeedback } from '@/composables/useFeedback.js'
import {
  statusMeta,
  historyNoteMeta,
  REFUND_ELIGIBLE_STATUSES,
  formatDate,
  formatMoney,
  courseItemMeta,
} from '@/utils/orderStatus.js'
import { formatImageUrl, useFallbackImage, DEFAULT_PRODUCT_IMAGE } from '@/utils/image.js'
import FeedbackToast from '@/components/FeedbackToast.vue'
import EmptyState from '@/components/EmptyState.vue'
import AppPagination from '@/components/AppPagination.vue'
import PaymentInfoBox from '@/components/PaymentInfoBox.vue'

const router = useRouter()
const route = useRoute()
const cart = useCartStore()
const { redirectToEcpay } = useEcpayCheckout()

const orders = ref([])
const loading = ref(false)
const expandedId = ref(null)
const detailLoading = ref(null) // orderId currently fetching detail
const orderDetails = reactive({}) // orderId -> OrderDetailResponse（含 items、subscription）
const orderHistory = reactive({}) // orderId -> OrderStatusHistoryResponse[]

// UNPAID 訂單的自動取消時限，對齊後端 ORDER_UNPAID_TTL（PT30M）
const UNPAID_TTL_MINUTES = 30

const orderCategories = [
  { key: 'ALL', label: '全部訂單', statuses: null },
  { key: 'UNPAID', label: '待付款', statuses: ['UNPAID'] },
  { key: 'FAILED', label: '取消/退款', statuses: ['CANCELLED', 'FAILED', 'REFUNDED'] },
  { key: 'SUCCESS', label: '已完成訂單', statuses: ['PAID', 'SHIPPED', 'COMPLETED'] },
]
const activeCategory = ref(orderCategories[0].key)
const keyword = ref('')

// ── 前端分頁：訂單一次全撈（分類數字要用完整清單），只切顯示的那一頁 ──
const PAGE_SIZE = 8
const page = ref(1)

function ordersInCategory(category) {
  if (!category.statuses) return orders.value
  return orders.value.filter((order) => category.statuses.includes(order.status))
}

const filteredOrders = computed(() => {
  const category = orderCategories.find((c) => c.key === activeCategory.value)
  let list = ordersInCategory(category)
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter(
      (order) =>
        order.merchantTradeNo.toLowerCase().includes(kw) ||
        (order.title?.toLowerCase().includes(kw) ?? false),
    )
  }
  return list
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredOrders.value.length / PAGE_SIZE)))
const pagedOrders = computed(() =>
  filteredOrders.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE),
)

// 切換分類或改關鍵字時回到第 1 頁
watch([activeCategory, keyword], () => {
  page.value = 1
})

function categoryCount(category) {
  return ordersInCategory(category).length
}

const { feedback, showFeedback } = useFeedback()

// 訂單卡片標題：優先顯示購買內容（後端算好的 title），沒有才退回訂單編號
function orderTitle(order) {
  return order.title || order.merchantTradeNo
}

// 發票資訊顯示文字；invoice 為 null 代表選了「個人（會員載具）」
const INVOICE_TYPE_LABELS = {
  PERSONAL_CARRIER: '個人（會員載具）',
  MOBILE_BARCODE: '手機條碼載具',
  COMPANY: '公司統編',
  NPO_DONATION: '捐贈發票',
}
function invoiceText(invoice) {
  if (!invoice) return '電子發票（個人／會員載具）'
  const label = INVOICE_TYPE_LABELS[invoice.type] || invoice.type
  if (invoice.type === 'MOBILE_BARCODE') return `${label}　${invoice.carrier}`
  if (invoice.type === 'COMPANY') return `${label}　統編 ${invoice.taxId}`
  if (invoice.type === 'NPO_DONATION') return `${label}　愛心碼 ${invoice.donationCode}`
  return label
}

// UNPAID 訂單的付款截止時間（建單時間 + TTL），用於顯示倒數提示
function unpaidDeadline(order) {
  return new Date(new Date(order.createdAt).getTime() + UNPAID_TTL_MINUTES * 60 * 1000)
}

async function loadOrders({ silent = false } = {}) {
  if (!silent) loading.value = true
  try {
    const res = await getMyOrders()
    orders.value = res.data
  } finally {
    if (!silent) loading.value = false
  }
}

// ── 未付款訂單：前往綠界付款 ────────────────────────────────────────────
const payingId = ref(null)

async function payForOrder(order) {
  payingId.value = order.orderId
  try {
    // 取綠界表單 → 記下待確認訂單 → 全屏過場 → 送出導向綠界（見 useEcpayCheckout）
    await redirectToEcpay(order.orderId)
    // 送出表單後瀏覽器即將導向綠界，故意不重設 payingId
  } catch (err) {
    showFeedback(err.message || '無法前往付款，請稍後再試', 'error')
    payingId.value = null
    // 最常見的失敗原因是「這筆其實已付款，畫面還停在舊狀態」（付款與返回同秒、
    // 自動查詢慢半拍）——重載列表讓狀態自己校正，使用者不用手動重新整理
    await loadOrders({ silent: true })
  }
}

// ── 從綠界返回後：主動查詢一次真實付款結果 ──────────────────────────────
// 本機沒有對外 URL，綠界的 server-to-server 付款結果通知打不進來（見後端 EcpayService#handleNotify）；
// 改成使用者從綠界返回這個「剛好回到網站」的時機，呼叫 QueryTradeInfo 查一次真實結果
// （見 EcpayService#syncPaymentStatus）。綠界文件明講查詢 API 有頻率限制、不建議做輪詢，
// 所以這裡只查一次，不重試——查不到就交給使用者自己在訂單明細按「查詢付款結果」手動再查。
const pendingPaymentOrderId = ref(null)

function clearPendingPayment() {
  pendingPaymentOrderId.value = null
  localStorage.removeItem('pendingPaymentOrderId')
}

async function syncPendingPayment() {
  const targetId = pendingPaymentOrderId.value
  if (!targetId) return
  try {
    const res = await syncOrderPayment(targetId)
    const order = orders.value.find((o) => o.orderId === targetId)
    if (order) {
      order.status = res.data.status
      order.totalAmount = res.data.totalAmount
    }
    if (
      res.data.status === 'PAID' ||
      res.data.status === 'COMPLETED' ||
      res.data.status === 'SHIPPED'
    ) {
      showFeedback('付款成功，訂單已更新')
    } else {
      showFeedback(
        '尚未查到付款成功的結果，若已完成付款請稍後在訂單明細按「查詢付款結果」再查一次',
        'error',
      )
    }
  } catch (err) {
    showFeedback(err.message || '查詢付款結果失敗，請稍後在訂單明細內手動查詢', 'error')
  } finally {
    clearPendingPayment()
  }
}

// ── 訂單明細內手動「查詢付款結果」：信用卡查詢失敗時重試，或 ATM/超商代碼繳費完成後主動查一次 ──
const syncingId = ref(null)

async function syncPayment(order) {
  syncingId.value = order.orderId
  try {
    const res = await syncOrderPayment(order.orderId)
    order.status = res.data.status
    order.totalAmount = res.data.totalAmount
    if (orderDetails[order.orderId]) orderDetails[order.orderId] = res.data
    if (
      res.data.status === 'PAID' ||
      res.data.status === 'COMPLETED' ||
      res.data.status === 'SHIPPED'
    ) {
      showFeedback('查詢成功，付款已確認')
      const historyRes = await getOrderHistory(order.orderId)
      orderHistory[order.orderId] = historyRes.data
    } else {
      showFeedback('目前查詢不到付款成功的結果，若已完成繳費請稍後再查詢一次', 'error')
    }
  } catch (err) {
    showFeedback(err.message || '查詢失敗，請稍後再試', 'error')
  } finally {
    syncingId.value = null
  }
}

async function toggleExpand(order) {
  if (expandedId.value === order.orderId) {
    expandedId.value = null
    return
  }
  expandedId.value = order.orderId
  if (!orderDetails[order.orderId]) {
    detailLoading.value = order.orderId
    try {
      const [detailRes, historyRes] = await Promise.all([
        getOrderById(order.orderId),
        getOrderHistory(order.orderId),
      ])
      orderDetails[order.orderId] = detailRes.data
      orderHistory[order.orderId] = historyRes.data
    } finally {
      detailLoading.value = null
    }
  }
}

// 送出取消／退款申請後，把列表與展開中的明細、時間軸一起更新
async function applyCancelResult(order, res) {
  order.status = res.data.status
  order.totalAmount = res.data.totalAmount
  if (orderDetails[order.orderId]) orderDetails[order.orderId] = res.data
  const historyRes = await getOrderHistory(order.orderId)
  orderHistory[order.orderId] = historyRes.data
}

function handleCancelOrRefund(order) {
  // 已付款／已出貨／已完成：走退款／退貨申請，需填原因給客服審核
  if (REFUND_ELIGIBLE_STATUSES.includes(order.status)) {
    openRefundModal(order)
    return
  }
  cancelUnpaidOrder(order)
}

async function cancelUnpaidOrder(order) {
  if (!window.confirm('確定要取消這筆未付款訂單嗎？')) return
  try {
    // 每次確認送出都用新的一次性金鑰，同一次確認若被重複觸發（雙擊、網路重送）只會真正執行一次
    const res = await apiCancelOrder(order.orderId, {}, crypto.randomUUID())
    await applyCancelResult(order, res)
    showFeedback('訂單已取消')
  } catch (err) {
    // 攔截器已正規化錯誤，訊息在 err.message；err.response 不存在（見 normalizeApiError）
    showFeedback(err.message || '操作失敗，請稍後再試', 'error')
  }
}

// 退款／退貨申請彈窗：原因分類 + 詳細說明，送出後理由帶進 PaymentRefund.reason，
// 進到後台「退款處理」頁供客服審核
const refundReasonCategories = [
  '商品有瑕疵或運送損壞',
  '收到的商品與描述不符',
  '收到錯誤的商品／數量',
  '不需要了／重複下單',
  '其他原因',
]
const refundModal = reactive({
  open: false,
  order: null,
  category: refundReasonCategories[0],
  detail: '',
  error: '',
  submitting: false,
})

function openRefundModal(order) {
  refundModal.open = true
  refundModal.order = order
  refundModal.category = refundReasonCategories[0]
  refundModal.detail = ''
  refundModal.error = ''
  refundModal.submitting = false
}

function closeRefundModal() {
  if (refundModal.submitting) return
  refundModal.open = false
  refundModal.order = null
}

async function submitRefundRequest() {
  const detail = refundModal.detail.trim()
  if (!detail) {
    refundModal.error = '請填寫詳細說明，方便客服人員審核'
    return
  }
  refundModal.error = ''
  refundModal.submitting = true
  const order = refundModal.order
  const reason = `【${refundModal.category}】${detail}`
  try {
    const res = await apiCancelOrder(order.orderId, { reason }, crypto.randomUUID())
    await applyCancelResult(order, res)
    refundModal.open = false
    refundModal.order = null
    showFeedback('退款／退貨申請已送出，客服將盡快為您審核')
  } catch (err) {
    refundModal.error = err.message || '操作失敗，請稍後再試'
  } finally {
    refundModal.submitting = false
  }
}

// 課程/私教明細（productId 為 null）不走購物車，只有商品明細能「再買一次」
function hasReorderableItems(order) {
  return orderDetails[order.orderId]?.items?.some((item) => item.productId != null) ?? false
}

const reorderingId = ref(null)

async function handleReorder(order) {
  const items = orderDetails[order.orderId]?.items ?? []
  const productItems = items.filter((item) => item.productId != null)
  if (productItems.length === 0) return

  reorderingId.value = order.orderId
  try {
    for (const item of productItems) {
      const res = await cart.addItem(item.productId, item.quantity)
      if (!res.success) {
        showFeedback(`「${item.productNameAtBuy}」加入購物車失敗：${res.message}`, 'error')
        return
      }
    }
    const skippedCount = items.length - productItems.length
    showFeedback(
      skippedCount > 0
        ? `已將 ${productItems.length} 項商品加入購物車（課程品項需另外預約，不支援加入購物車）`
        : '已將商品加入購物車',
    )
    router.push('/cart')
  } finally {
    reorderingId.value = null
  }
}

// 系統層若關閉動態效果，展開／收合就不做高度過場，直接切換
const reduceMotion =
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

// ── 訂單明細展開／收合：以實際內容高度做 height 過場，避免內容多寡造成生硬跳出 ──
function onDetailEnter(el, done) {
  if (reduceMotion) return done()
  el.style.height = '0'
  el.style.opacity = '0'
  el.style.overflow = 'hidden'
  void el.offsetHeight
  el.style.transition = 'height 0.26s ease, opacity 0.26s ease'
  el.style.height = `${el.scrollHeight}px`
  el.style.opacity = '1'
  setTimeout(done, 280)
}
function onDetailAfterEnter(el) {
  el.style.height = ''
  el.style.opacity = ''
  el.style.overflow = ''
  el.style.transition = ''
}
function onDetailLeave(el, done) {
  if (reduceMotion) return done()
  el.style.height = `${el.scrollHeight}px`
  el.style.overflow = 'hidden'
  void el.offsetHeight
  el.style.transition = 'height 0.22s ease, opacity 0.22s ease'
  el.style.height = '0'
  el.style.opacity = '0'
  setTimeout(done, 240)
}

// 深連結進來時，捲到目標卡片後短暫高亮一下，幫使用者定位是哪一筆
const highlightedId = ref(null)
let highlightTimer = null

onUnmounted(() => {
  clearTimeout(highlightTimer)
})

// DefaultLayout 用 <keep-alive> 快取這頁：離開再回來不會重新掛載，付完款／申請退款後
// 回來會停在舊狀態。onActivated 在首次掛載也會觸發（那次由 onMounted 負責），用旗標略過。
let activatedBefore = false
onActivated(() => {
  if (activatedBefore) loadOrders()
  activatedBefore = true
})

onMounted(async () => {
  await loadOrders()

  // 深連結 /orders/:id：切到「全部訂單」、翻到該筆所在的分頁，再展開並捲動過去
  const deepLinkId = route.params.id ? Number(route.params.id) : null
  if (deepLinkId && orders.value.some((o) => o.orderId === deepLinkId)) {
    activeCategory.value = 'ALL'
    keyword.value = ''
    await nextTick()
    const idx = filteredOrders.value.findIndex((o) => o.orderId === deepLinkId)
    if (idx >= 0) page.value = Math.floor(idx / PAGE_SIZE) + 1
    await toggleExpand({ orderId: deepLinkId })
    await nextTick()
    document
      .querySelector(`[data-order-id="${deepLinkId}"]`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    highlightedId.value = deepLinkId
    highlightTimer = setTimeout(() => {
      highlightedId.value = null
    }, 2400)
  }

  // 從綠界返回：若有待確認付款的訂單，主動查詢一次真實付款結果。
  // 記號存 localStorage 而不是 sessionStorage——sessionStorage 是分頁隔離的，
  // 使用者若沒走綠界的「返回特店」、而是在別的分頁自己打開訂單頁，記號就看不到，
  // 自動查詢永遠不會發生（實際發生過，訂單一直顯示待付款）。
  const pending = localStorage.getItem('pendingPaymentOrderId')
  if (pending) {
    const pendingId = Number(pending)
    const order = orders.value.find((o) => o.orderId === pendingId)
    if (order && order.status === 'UNPAID') {
      pendingPaymentOrderId.value = pendingId
      await syncPendingPayment()
    } else {
      localStorage.removeItem('pendingPaymentOrderId')
    }
  }
})
</script>

<template>
  <div class="page-container">
    <div class="section-header">
      <span class="section-tag">MY ORDERS</span>
      <h1 class="section-title">我的訂單</h1>
      <p class="section-desc">查看歷史訂單狀態與明細，可於此付款、取消訂單或申請退款/退貨。</p>
    </div>

    <div v-if="pendingPaymentOrderId" class="payment-checking">
      <span class="spinner-inline"></span>
      正在確認付款結果，請稍候…（若已完成付款，訂單狀態會自動更新）
    </div>

    <div v-if="loading" class="order-list" aria-hidden="true">
      <div v-for="n in 4" :key="n" class="order-skeleton-card">
        <div class="skeleton skeleton-line" style="width: 42%"></div>
        <div class="skeleton skeleton-line skeleton-line--sm" style="width: 26%"></div>
        <div class="skeleton skeleton-pill"></div>
      </div>
    </div>

    <EmptyState
      v-else-if="orders.length === 0"
      icon="orders"
      title="目前沒有任何訂單"
      description="完成購買後，就能在這裡追蹤訂單狀態、查看明細與申請退款／退貨"
    >
      <template #action>
        <RouterLink to="/products" class="btn btn-accent">去逛逛</RouterLink>
      </template>
    </EmptyState>

    <template v-else>
      <div class="order-toolbar">
        <div class="order-tabs">
          <button
            v-for="category in orderCategories"
            :key="category.key"
            class="order-tab"
            :class="{ active: activeCategory === category.key }"
            @click="activeCategory = category.key"
          >
            {{ category.label }}
            <span v-if="categoryCount(category)" class="tab-count">{{
              categoryCount(category)
            }}</span>
          </button>
        </div>

        <input
          v-model="keyword"
          class="order-search"
          type="text"
          placeholder="搜尋訂單編號或購買內容..."
          aria-label="搜尋訂單編號或購買內容"
        />
      </div>

      <EmptyState
        v-if="filteredOrders.length === 0"
        icon="search"
        :title="keyword.trim() ? '找不到符合的訂單' : '此分類目前沒有訂單'"
        :description="
          keyword.trim() ? '換個關鍵字，或改用訂單編號查詢' : '切換上方分類看看其他訂單'
        "
      />

      <div v-else class="order-list">
        <div
          v-for="order in pagedOrders"
          :key="order.orderId"
          class="order-card"
          :class="[
            `accent-${statusMeta[order.status]?.className || 'muted'}`,
            { 'is-highlighted': highlightedId === order.orderId },
          ]"
          :data-order-id="order.orderId"
        >
          <button class="order-header" @click="toggleExpand(order)">
            <div class="order-header-main">
              <span class="order-title" :title="`訂單編號　${order.merchantTradeNo}`">
                {{ orderTitle(order) }}
              </span>
              <span class="order-date">{{ formatDate(order.createdAt) }}</span>
            </div>
            <div class="order-header-side">
              <span class="status-badge" :class="statusMeta[order.status]?.className">
                {{ statusMeta[order.status]?.label || order.status }}
              </span>
              <span class="order-amount">NT$ {{ Number(order.totalAmount).toLocaleString() }}</span>
              <span
                class="expand-icon"
                :class="{ 'is-open': expandedId === order.orderId }"
                aria-hidden="true"
                >▾</span
              >
            </div>
          </button>

          <Transition
            :css="false"
            @enter="onDetailEnter"
            @after-enter="onDetailAfterEnter"
            @leave="onDetailLeave"
          >
            <div v-if="expandedId === order.orderId" class="order-detail">
              <div v-if="detailLoading === order.orderId" class="detail-loading">載入中...</div>
              <template v-else-if="orderDetails[order.orderId]">
                <div class="detail-section">
                  <h4 class="detail-heading">購買內容</h4>

                  <div v-if="orderDetails[order.orderId].subscription" class="detail-item-row">
                    <div class="detail-item-info">
                      <span class="detail-item-name">{{
                        orderDetails[order.orderId].subscription.name
                      }}</span>
                      <span class="detail-item-meta">
                        會員訂閱方案・{{ orderDetails[order.orderId].subscription.durationDays }}
                        天使用期
                      </span>
                      <span
                        v-if="orderDetails[order.orderId].subscription.description"
                        class="detail-item-desc"
                        >{{ orderDetails[order.orderId].subscription.description }}</span
                      >
                    </div>
                    <span class="detail-item-amount">
                      {{ formatMoney(orderDetails[order.orderId].subscription.price) }}
                    </span>
                  </div>

                  <div
                    v-for="item in orderDetails[order.orderId].items"
                    :key="item.orderItemId"
                    class="detail-item-row"
                  >
                    <div class="detail-item-thumb" v-if="item.itemType !== 'COURSE'">
                      <img
                        v-if="item.imageUrl"
                        :src="formatImageUrl(item.imageUrl)"
                        :alt="item.productNameAtBuy"
                        @error="useFallbackImage($event, DEFAULT_PRODUCT_IMAGE)"
                      />
                      <span v-else>{{ item.productNameAtBuy?.[0] }}</span>
                    </div>
                    <div class="detail-item-info">
                      <span class="detail-item-name">
                        {{ item.productNameAtBuy
                        }}<span class="detail-item-qty">× {{ item.quantity }}</span>
                      </span>
                      <span v-if="item.itemType === 'COURSE'" class="detail-item-meta">
                        私教課程<template v-if="courseItemMeta(item)"
                          >・{{ courseItemMeta(item) }}</template
                        >
                      </span>
                      <span v-if="item.quantity > 1" class="detail-item-meta">
                        單價 {{ formatMoney(item.priceAtBuy) }}
                      </span>
                    </div>
                    <span class="detail-item-amount">{{ formatMoney(item.lineAmount) }}</span>
                  </div>

                  <div class="detail-amount-lines">
                    <div class="detail-amount-row">
                      <span>商品小計</span>
                      <span>{{ formatMoney(orderDetails[order.orderId].subtotalAmount) }}</span>
                    </div>
                    <div
                      v-if="Number(orderDetails[order.orderId].discountAmount) > 0"
                      class="detail-amount-row discount"
                    >
                      <span>
                        優惠折抵<template v-if="orderDetails[order.orderId].couponName"
                          >（{{ orderDetails[order.orderId].couponName }}）</template
                        >
                      </span>
                      <span>− {{ formatMoney(orderDetails[order.orderId].discountAmount) }}</span>
                    </div>
                    <div class="detail-total-row">
                      <span>訂單金額</span>
                      <span>{{ formatMoney(order.totalAmount) }}</span>
                    </div>
                  </div>
                </div>

                <div class="detail-section">
                  <h4 class="detail-heading">發票資訊</h4>
                  <p class="detail-invoice">
                    {{ invoiceText(orderDetails[order.orderId].invoice) }}
                  </p>
                </div>

                <p v-if="order.status === 'UNPAID'" class="unpaid-deadline">
                  請於 {{ formatDate(unpaidDeadline(order)) }} 前完成付款，逾時系統將自動取消訂單。
                </p>

                <PaymentInfoBox
                  v-if="orderDetails[order.orderId].paymentInfo"
                  :payment-info="orderDetails[order.orderId].paymentInfo"
                />

                <div
                  class="detail-actions"
                  v-if="
                    hasReorderableItems(order) ||
                    order.status === 'UNPAID' ||
                    REFUND_ELIGIBLE_STATUSES.includes(order.status)
                  "
                >
                  <button
                    v-if="order.status === 'UNPAID'"
                    class="btn btn-accent pay-btn"
                    :disabled="payingId === order.orderId"
                    @click="payForOrder(order)"
                  >
                    {{ payingId === order.orderId ? '前往付款中…' : '前往付款' }}
                  </button>
                  <button
                    v-if="order.status === 'UNPAID'"
                    class="btn btn-outline sync-btn"
                    :disabled="syncingId === order.orderId"
                    @click="syncPayment(order)"
                  >
                    {{ syncingId === order.orderId ? '查詢中…' : '查詢付款結果' }}
                  </button>
                  <button
                    v-if="hasReorderableItems(order)"
                    class="btn btn-outline reorder-btn"
                    :disabled="reorderingId === order.orderId"
                    @click="handleReorder(order)"
                  >
                    {{
                      reorderingId === order.orderId
                        ? '加入中…'
                        : order.status === 'UNPAID'
                          ? '把商品加回購物車'
                          : '再買一次'
                    }}
                  </button>
                  <button
                    v-if="
                      order.status === 'UNPAID' || REFUND_ELIGIBLE_STATUSES.includes(order.status)
                    "
                    class="btn btn-outline cancel-btn"
                    @click="handleCancelOrRefund(order)"
                  >
                    {{ order.status === 'UNPAID' ? '取消訂單' : '申請退款/退貨' }}
                  </button>
                </div>

                <div class="order-timeline" v-if="orderHistory[order.orderId]?.length">
                  <h4 class="timeline-title">訂單狀態時間軸</h4>
                  <ul class="timeline-list">
                    <li
                      v-for="(event, idx) in orderHistory[order.orderId]"
                      :key="idx"
                      class="timeline-item"
                    >
                      <span
                        class="timeline-dot"
                        :class="statusMeta[event.toStatus]?.className"
                      ></span>
                      <div class="timeline-body">
                        <div class="timeline-row">
                          <span class="timeline-note">{{
                            historyNoteMeta[event.note] || event.note
                          }}</span>
                          <span
                            class="status-badge timeline-badge"
                            :class="statusMeta[event.toStatus]?.className"
                          >
                            {{ statusMeta[event.toStatus]?.label || event.toStatus }}
                          </span>
                        </div>
                        <span class="timeline-time">{{ formatDate(event.changedAt) }}</span>
                      </div>
                    </li>
                  </ul>
                </div>
              </template>
              <div v-else class="detail-loading">訂單明細載入失敗，請收合後再展開重試</div>
            </div>
          </Transition>
        </div>

        <AppPagination
          v-if="filteredOrders.length > 0"
          :model-value="page"
          :total-pages="totalPages"
          :total-elements="filteredOrders.length"
          :page-size="PAGE_SIZE"
          @change="(e) => (page = e.page)"
        />
      </div>
    </template>

    <div v-if="refundModal.open" class="modal-overlay" @click.self="closeRefundModal">
      <div class="modal-content">
        <h3 class="modal-title">申請退款/退貨</h3>
        <p class="modal-desc">
          訂單編號 {{ refundModal.order?.merchantTradeNo }}／可退金額
          {{ formatMoney(refundModal.order?.totalAmount) }}。
          請說明退款或退貨的原因，送出後將由客服人員審核處理。
        </p>

        <div class="modal-field">
          <label for="refund-category">原因類型</label>
          <select
            id="refund-category"
            v-model="refundModal.category"
            :disabled="refundModal.submitting"
          >
            <option v-for="c in refundReasonCategories" :key="c" :value="c">{{ c }}</option>
          </select>
        </div>

        <div class="modal-field">
          <label for="refund-detail">詳細說明<span class="required">＊</span></label>
          <textarea
            id="refund-detail"
            v-model="refundModal.detail"
            rows="4"
            maxlength="400"
            :disabled="refundModal.submitting"
            placeholder="請具體說明狀況，例如商品哪裡有問題、希望退款或換貨，方便客服盡快為您處理"
          ></textarea>
          <span class="modal-count">{{ refundModal.detail.length }} / 400</span>
        </div>

        <p v-if="refundModal.error" class="modal-error">{{ refundModal.error }}</p>

        <div class="modal-actions">
          <button
            class="btn btn-accent"
            type="button"
            :disabled="refundModal.submitting"
            @click="submitRefundRequest"
          >
            {{ refundModal.submitting ? '送出中…' : '送出申請' }}
          </button>
          <button
            class="btn btn-outline"
            type="button"
            :disabled="refundModal.submitting"
            @click="closeRefundModal"
          >
            返回
          </button>
        </div>
      </div>
    </div>

    <FeedbackToast :feedback="feedback" />
  </div>
</template>

<style scoped>
.order-toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
  border-bottom: 1px solid var(--border);
}
.order-tabs {
  display: flex;
  gap: 0.5rem;
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
.order-tab:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
  border-radius: 4px;
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
.order-search {
  width: 100%;
  max-width: 240px;
  margin-bottom: 0.6rem;
  padding: 0.5rem 0.8rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.85rem;
  color: var(--text-primary);
  transition: var(--transition);
}
.order-search:focus,
.order-search:focus-visible {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(201, 154, 91, 0.15);
}
.order-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  margin-top: 1.25rem;
}
.order-card {
  position: relative;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-section);
  overflow: hidden;
}
/* 依訂單狀態在左緣加一條色條，不用逐張讀 badge 就能掃出待付款／已完成 */
.order-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
  background: var(--status-color, var(--border));
}
.order-card.accent-warning {
  --status-color: #e0a800;
}
.order-card.accent-info {
  --status-color: #2563eb;
}
.order-card.accent-success {
  --status-color: #2f9e44;
}
.order-card.accent-danger {
  --status-color: #e03131;
}
.order-card.accent-muted {
  --status-color: var(--border);
}
.order-card.is-highlighted {
  animation: order-card-pulse 2.2s ease;
}
@keyframes order-card-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(201, 154, 91, 0);
  }
  12% {
    box-shadow: 0 0 0 4px rgba(201, 154, 91, 0.4);
  }
}
.order-skeleton-card {
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
  padding: 1.15rem 1.25rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-section);
}
.skeleton-line {
  height: 0.85rem;
  border-radius: 4px;
}
.skeleton-line--sm {
  height: 0.7rem;
}
.skeleton-pill {
  width: 5rem;
  height: 1.5rem;
  border-radius: 999px;
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
.order-header:focus-visible {
  outline: none;
  box-shadow: inset 0 0 0 2px var(--accent);
}
.order-header-main {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.order-title {
  font-size: 0.95rem;
  font-weight: 700;
  color: var(--text-primary);
  overflow-wrap: anywhere;
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
.status-badge.warning {
  background: #fff3cd;
  color: #a06400;
}
.status-badge.info {
  background: #dbeafe;
  color: #1d4ed8;
}
.status-badge.success {
  background: #d4f4dd;
  color: #157347;
}
.status-badge.danger {
  background: #fde2e2;
  color: #c92a2a;
}
.status-badge.muted {
  background: var(--bg-soft);
  color: var(--text-muted);
}
.order-amount {
  font-weight: 700;
  color: var(--accent-hover);
}
.expand-icon {
  font-size: 0.85rem;
  line-height: 1;
  color: var(--text-muted);
  transition: transform 0.2s ease;
}
.expand-icon.is-open {
  transform: rotate(180deg);
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
  margin-bottom: 0.5rem;
}
.detail-heading {
  font-size: 0.8rem;
  font-weight: 700;
  color: var(--text-muted);
  margin-bottom: 0.6rem;
}
.detail-item-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 0.85rem;
  font-size: 0.88rem;
  color: var(--text-secondary);
  padding: 0.5rem 0;
  border-bottom: 1px dashed var(--border);
}
.detail-item-thumb {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--bg-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.9rem;
  color: var(--text-muted);
}
.detail-item-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.detail-item-info {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  min-width: 0;
  flex: 1;
}
.detail-item-name {
  font-weight: 600;
  color: var(--text-primary);
}
.detail-item-qty {
  margin-left: 0.4rem;
  font-weight: 400;
  color: var(--text-secondary);
}
.detail-item-meta {
  font-size: 0.78rem;
  color: var(--text-muted);
}
.detail-item-desc {
  font-size: 0.78rem;
  color: var(--text-secondary);
  line-height: 1.5;
  margin-top: 0.1rem;
}
.detail-item-amount {
  flex-shrink: 0;
  font-weight: 600;
  color: var(--text-primary);
}
.detail-amount-lines {
  padding-top: 0.5rem;
}
.detail-amount-row {
  display: flex;
  justify-content: space-between;
  font-size: 0.85rem;
  color: var(--text-secondary);
  padding: 0.15rem 0;
}
.detail-amount-row.discount {
  color: #c92a2a;
}
.detail-total-row {
  display: flex;
  justify-content: space-between;
  padding: 0.5rem 0 0.2rem;
  margin-top: 0.25rem;
  border-top: 1px solid var(--border);
  font-size: 0.9rem;
  font-weight: 700;
  color: var(--text-primary);
}
.detail-invoice {
  font-size: 0.86rem;
  color: var(--text-secondary);
}
.unpaid-deadline {
  margin-top: 0.75rem;
  padding: 0.55rem 0.8rem;
  border-radius: var(--radius-sm);
  background: #fff3cd;
  font-size: 0.8rem;
  font-weight: 600;
  color: #a06400;
}
.detail-actions {
  margin-top: 0.75rem;
  padding-top: 0.75rem;
  border-top: 1px dashed var(--border);
  display: flex;
  justify-content: flex-end;
  gap: 0.6rem;
}
.cancel-btn,
.reorder-btn,
.pay-btn,
.sync-btn {
  font-size: 0.85rem;
  padding: 0.45rem 1.1rem;
}
.payment-checking {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  margin-top: 1rem;
  padding: 0.7rem 1rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #dbeafe;
  color: #1d4ed8;
  font-size: 0.88rem;
}
.spinner-inline {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(29, 78, 216, 0.3);
  border-top-color: #1d4ed8;
  border-radius: 50%;
  animation: spin-inline 0.8s linear infinite;
  flex-shrink: 0;
}
@keyframes spin-inline {
  to {
    transform: rotate(360deg);
  }
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
.timeline-dot.warning {
  background: #a06400;
}
.timeline-dot.info {
  background: #1d4ed8;
}
.timeline-dot.success {
  background: #157347;
}
.timeline-dot.danger {
  background: #c92a2a;
}
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

/* 系統層關閉動態效果時，收起裝飾性動畫（展開高度過場已在 script 內以 reduceMotion 跳過） */
@media (prefers-reduced-motion: reduce) {
  .expand-icon {
    transition: none;
  }
  .order-card.is-highlighted {
    animation: none;
  }
}

@media (max-width: 640px) {
  .order-header {
    flex-direction: column;
    align-items: flex-start;
  }
  .order-header-side {
    width: 100%;
    justify-content: space-between;
  }
  .order-toolbar {
    flex-direction: column;
    align-items: stretch;
  }
  .order-search {
    max-width: none;
    margin: 0.75rem 0;
  }
}

.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  z-index: 100;
}
.modal-content {
  width: 100%;
  max-width: 460px;
  max-height: 88vh;
  overflow-y: auto;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-hover);
  padding: 1.75rem;
}
.modal-title {
  margin: 0 0 0.75rem;
  font-size: 1.15rem;
  color: var(--text-primary);
}
.modal-desc {
  font-size: 0.85rem;
  line-height: 1.6;
  color: var(--text-secondary);
  margin-bottom: 1.25rem;
}
.modal-field {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  margin-bottom: 1rem;
}
.modal-field label {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-primary);
}
.modal-field .required {
  color: #c92a2a;
  margin-left: 0.15rem;
}
.modal-field select,
.modal-field textarea {
  width: 100%;
  padding: 0.55rem 0.8rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font: inherit;
  font-size: 0.88rem;
  color: var(--text-primary);
  background: var(--bg-section);
}
.modal-field textarea {
  resize: vertical;
  min-height: 5rem;
  line-height: 1.5;
}
.modal-count {
  align-self: flex-end;
  font-size: 0.72rem;
  color: var(--text-muted);
}
.modal-error {
  font-size: 0.82rem;
  color: #c92a2a;
  margin: 0 0 0.75rem;
}
.modal-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 0.5rem;
}
.modal-actions .btn {
  flex: 1;
}
</style>
