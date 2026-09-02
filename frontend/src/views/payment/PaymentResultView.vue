<script setup>
defineOptions({ name: 'PaymentResultView' })
import { ref, computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { getOrderById, syncOrderPayment } from '@/api/order.js'
import { formatMoney, statusLabel, PAID_STATUSES } from '@/utils/orderStatus.js'
import { formatImageUrl, useFallbackImage, DEFAULT_PRODUCT_IMAGE } from '@/utils/image.js'
import { useFeedback } from '@/composables/useFeedback.js'
import FeedbackToast from '@/components/FeedbackToast.vue'
import PaymentInfoBox from '@/components/PaymentInfoBox.vue'

// 綠界付款頁「返回商店」會落到這裡（app.ecpay.client-back-url）。
// 待確認的訂單 id 在結帳時寫進 localStorage（useEcpayCheckout），這裡讀出來主動同步一次結果。
const status = ref('checking') // 'checking' | 'paid' | 'pending' | 'failed' | 'unknown'
const order = ref(null)
const orderId = ref(null)
const syncing = ref(false)

const { feedback, showFeedback } = useFeedback()

const INVOICE_TYPE_LABELS = {
  PERSONAL_CARRIER: '個人（會員載具）',
  MOBILE_BARCODE: '手機條碼載具',
  COMPANY: '公司統編',
  NPO_DONATION: '捐贈發票',
}
const invoiceText = computed(() => {
  const inv = order.value?.invoice
  if (!inv) return '電子發票（個人／會員載具）'
  const label = INVOICE_TYPE_LABELS[inv.type] || inv.type
  if (inv.type === 'MOBILE_BARCODE') return `${label}　${inv.carrier}`
  if (inv.type === 'COMPANY') return `${label}　統編 ${inv.taxId}`
  if (inv.type === 'NPO_DONATION') return `${label}　愛心碼 ${inv.donationCode}`
  return label
})

function classifyOrder(data) {
  if (PAID_STATUSES.includes(data.status)) return 'paid'
  if (data.status === 'UNPAID') return 'pending'
  return 'failed'
}

async function refresh() {
  if (!orderId.value) return
  try {
    const { data } = await getOrderById(orderId.value)
    order.value = data
    status.value = classifyOrder(data)
  } catch {
    status.value = 'unknown'
  }
}

async function reSync() {
  syncing.value = true
  try {
    await syncOrderPayment(orderId.value)
    await refresh()
    if (status.value === 'paid') showFeedback('付款已確認')
    else showFeedback('尚未查到付款成功的結果，若已完成繳費請稍後再查詢', 'error')
  } catch (err) {
    showFeedback(err.message || '查詢失敗，請稍後再試', 'error')
  } finally {
    syncing.value = false
  }
}

onMounted(async () => {
  // 清掉可能帶在網址上的綠界參數
  window.history.replaceState({}, '', '/payment/result')

  const raw = localStorage.getItem('pendingPaymentOrderId')
  if (!raw) {
    status.value = 'unknown'
    return
  }
  orderId.value = Number(raw)
  // 本機收不到綠界 server-to-server 通知，趁使用者剛返回主動查一次真實結果
  try {
    await syncOrderPayment(orderId.value)
  } catch {
    // 查詢失敗不擋，下面仍讀一次訂單現況
  }
  await refresh()
  localStorage.removeItem('pendingPaymentOrderId')
})
</script>

<template>
  <main class="pay-result-page">
    <section class="pay-result-card">
      <template v-if="status === 'checking'">
        <div class="spinner"></div>
        <p class="pay-result-desc">正在確認付款結果，請稍候…</p>
      </template>

      <template v-else>
        <div class="pay-result-head" :class="`is-${status}`">
          <span class="pay-result-icon" aria-hidden="true">
            {{ status === 'paid' ? '✓' : status === 'pending' ? '⏳' : '!' }}
          </span>
          <h1 class="pay-result-title">
            {{
              status === 'paid'
                ? '付款成功'
                : status === 'pending'
                  ? '訂單已建立，尚未完成付款'
                  : status === 'failed'
                    ? '付款未完成'
                    : '找不到付款資訊'
            }}
          </h1>
          <p class="pay-result-desc">
            {{
              status === 'paid'
                ? '感謝您的購買，訂單已成立。'
                : status === 'pending'
                  ? '請依下方繳費資訊完成付款，或稍後再查詢一次結果。'
                  : status === 'failed'
                    ? '這筆訂單目前為未付款或付款失敗，可至「我的訂單」重新付款。'
                    : '沒有待確認的付款，可能是已在其他頁面完成。'
            }}
          </p>
        </div>

        <div v-if="order" class="pay-result-summary">
          <div class="summary-row summary-row--title">
            <span>{{ order.title || order.merchantTradeNo }}</span>
            <span class="summary-status">{{ statusLabel(order.status) }}</span>
          </div>

          <div v-for="item in order.items" :key="item.orderItemId" class="summary-item">
            <div class="summary-item__thumb" v-if="item.itemType !== 'COURSE'">
              <img
                v-if="item.imageUrl"
                :src="formatImageUrl(item.imageUrl)"
                :alt="item.productNameAtBuy"
                @error="useFallbackImage($event, DEFAULT_PRODUCT_IMAGE)"
              />
              <span v-else>{{ item.productNameAtBuy?.[0] }}</span>
            </div>
            <span class="summary-item__name"
              >{{ item.productNameAtBuy }} × {{ item.quantity }}</span
            >
            <span class="summary-item__amount">{{ formatMoney(item.lineAmount) }}</span>
          </div>
          <div v-if="order.subscription" class="summary-item">
            <span class="summary-item__name">{{ order.subscription.name }}（會員訂閱方案）</span>
            <span class="summary-item__amount">{{ formatMoney(order.subscription.price) }}</span>
          </div>

          <div class="summary-amount-row">
            <span>商品小計</span><span>{{ formatMoney(order.subtotalAmount) }}</span>
          </div>
          <div v-if="Number(order.discountAmount) > 0" class="summary-amount-row discount">
            <span
              >優惠折抵<template v-if="order.couponName">（{{ order.couponName }}）</template></span
            >
            <span>− {{ formatMoney(order.discountAmount) }}</span>
          </div>
          <div class="summary-amount-row summary-amount-row--total">
            <span>應付金額</span><span>{{ formatMoney(order.totalAmount) }}</span>
          </div>

          <p class="summary-invoice">發票：{{ invoiceText }}</p>

          <PaymentInfoBox
            v-if="status === 'pending' && order.paymentInfo"
            :payment-info="order.paymentInfo"
          />
          <button
            v-if="status === 'pending'"
            type="button"
            class="btn btn-outline resync-btn"
            :disabled="syncing"
            @click="reSync"
          >
            {{ syncing ? '查詢中…' : '查詢付款結果' }}
          </button>
        </div>

        <div class="pay-result-actions">
          <RouterLink v-if="orderId" :to="`/orders/${orderId}`" class="btn btn-accent">
            查看訂單明細
          </RouterLink>
          <RouterLink v-else to="/orders" class="btn btn-accent">前往我的訂單</RouterLink>
          <RouterLink to="/products" class="btn btn-outline">繼續購物</RouterLink>
        </div>
      </template>
    </section>

    <FeedbackToast :feedback="feedback" />
  </main>
</template>

<style scoped>
.pay-result-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 3rem 1rem;
  background: var(--bg-body);
}
.pay-result-card {
  width: 100%;
  max-width: 32rem;
  padding: 2.2rem 2rem;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  box-shadow: 0 10px 30px rgba(60, 48, 43, 0.08);
  text-align: center;
}
.pay-result-card .spinner {
  margin: 1rem auto 1.25rem;
}
.pay-result-head {
  margin-bottom: 1.5rem;
}
.pay-result-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  font-size: 1.5rem;
  font-weight: 900;
  margin-bottom: 0.75rem;
}
.is-paid .pay-result-icon {
  background: #d4f4dd;
  color: #157347;
}
.is-pending .pay-result-icon {
  background: #fff3cd;
  color: #a06400;
}
.is-failed .pay-result-icon,
.is-unknown .pay-result-icon {
  background: #fde2e2;
  color: #c92a2a;
}
.pay-result-title {
  font-size: 1.35rem;
  font-weight: 900;
  color: var(--primary-dark);
}
.pay-result-desc {
  margin-top: 0.5rem;
  font-size: 0.9rem;
  line-height: 1.7;
  color: var(--text-secondary);
}
.pay-result-summary {
  text-align: left;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 1rem 1.1rem;
  margin-bottom: 1.5rem;
}
.summary-row--title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 700;
  color: var(--text-primary);
  padding-bottom: 0.6rem;
  border-bottom: 1px solid var(--border);
}
.summary-status {
  font-size: 0.76rem;
  padding: 0.15rem 0.55rem;
  border-radius: 999px;
  background: var(--bg-soft);
  color: var(--text-secondary);
}
.summary-item {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.5rem 0;
  font-size: 0.85rem;
  color: var(--text-secondary);
  border-bottom: 1px dashed var(--border);
}
.summary-item__thumb {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--bg-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
}
.summary-item__thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.summary-item__name {
  flex: 1;
  min-width: 0;
}
.summary-item__amount {
  flex-shrink: 0;
  font-weight: 600;
  color: var(--text-primary);
}
.summary-amount-row {
  display: flex;
  justify-content: space-between;
  font-size: 0.85rem;
  color: var(--text-secondary);
  padding: 0.2rem 0;
}
.summary-amount-row.discount {
  color: #c92a2a;
}
.summary-amount-row--total {
  margin-top: 0.3rem;
  padding-top: 0.5rem;
  border-top: 1px solid var(--border);
  font-weight: 700;
  font-size: 0.95rem;
  color: var(--text-primary);
}
.summary-invoice {
  margin-top: 0.6rem;
  font-size: 0.8rem;
  color: var(--text-muted);
}
.resync-btn {
  width: 100%;
  margin-top: 0.9rem;
  font-size: 0.85rem;
}
.pay-result-actions {
  display: flex;
  gap: 0.75rem;
}
.pay-result-actions .btn {
  flex: 1;
}
@media (max-width: 480px) {
  .pay-result-actions {
    flex-direction: column;
  }
}
</style>
