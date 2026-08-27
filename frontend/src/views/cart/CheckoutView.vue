<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useCartStore } from '@/stores/cart.js'
import { checkoutFromCart } from '@/api/order.js'
import { getEcpayCheckoutForm } from '@/api/payment.js'
import { getMyProfile } from '@/api/memberProfile.js'
import { submitEcpayForm } from '@/utils/ecpay.js'
import { useFeedback } from '@/composables/useFeedback.js'
import { taiwanDistricts } from '@/data/taiwanDistricts.js'
import FeedbackToast from '@/components/FeedbackToast.vue'

const cart = useCartStore()
const { items, totalAmount, loading } = storeToRefs(cart)

const couponCode = ref('')
const receiverName = ref('')
const receiverPhone = ref('')
const submitting = ref(false)
const showConfirm = ref(false)

// 收件地址拆成縣市/行政區下拉 + 詳細地址（路名、巷弄、門牌）自由輸入
const cityOptions = Object.keys(taiwanDistricts)
const receiverCity = ref('')
const receiverDistrict = ref('')
const receiverAddressDetail = ref('')
const districtOptions = computed(() => (receiverCity.value ? taiwanDistricts[receiverCity.value] ?? [] : []))

// 換縣市時，原本選的行政區不一定屬於新縣市，直接清空避免留著兜不起來的組合
watch(receiverCity, () => {
  receiverDistrict.value = ''
})

// 這次結帳的冪等金鑰：整個頁面存續期間固定不變，手滑連點「確認送出」
// 只會真正建立一次訂單；離開頁面重新進來（新的一次結帳）才會換一把新的
const idempotencyKey = crypto.randomUUID()

const { feedback, showFeedback } = useFeedback()

// 會員資料裡已存的地址，用「區塊式選項」讓使用者在它跟自訂收件資訊之間切換，
// 不會直接把文字灌進欄位——要改用別的地址時點一下切換就是空白表單，不用先刪掉舊字
const memberProfile = ref(null)
const receiverMode = ref('custom') // 'profile' | 'custom'

const hasProfileAddress = computed(() =>
  Boolean(memberProfile.value?.city || memberProfile.value?.district || memberProfile.value?.address),
)
const profileName = computed(() =>
  memberProfile.value ? `${memberProfile.value.firstName ?? ''}${memberProfile.value.lastName ?? ''}`.trim() : '',
)
const profilePhone = computed(() => memberProfile.value?.phone ?? '')
const profileAddress = computed(() =>
  memberProfile.value
    ? [memberProfile.value.city, memberProfile.value.district, memberProfile.value.address].filter(Boolean).join('')
    : '',
)

// 實際要送出/在確認彈窗顯示的收件資訊，依目前選的模式決定來源；
// 自訂模式的地址由縣市＋行政區＋詳細地址組成，台灣地址慣例不加空格
const effectiveReceiver = computed(() => {
  if (receiverMode.value === 'profile') {
    return { name: profileName.value, phone: profilePhone.value, address: profileAddress.value }
  }
  const address = `${receiverCity.value}${receiverDistrict.value}${receiverAddressDetail.value.trim()}`
  return { name: receiverName.value.trim(), phone: receiverPhone.value.trim(), address }
})

async function loadMemberProfile() {
  try {
    const { data } = await getMyProfile()
    memberProfile.value = data
    // 城市/行政區/詳細地址任一有值就當作有地址資料，預設選它，省得每次都要手動切
    if (data.city || data.district || data.address) receiverMode.value = 'profile'
  } catch {
    // 抓不到就維持自訂模式，使用者手動輸入即可
  }
}

// 購物車有任一品項已下架或庫存不足時擋下結帳
const hasUnavailableItem = computed(() => items.value.some((i) => i.available === false))

// 按「確認結帳並前往付款」先開確認彈窗讓使用者再檢查一次，不是直接送出
function openConfirm() {
  if (items.value.length === 0) {
    showFeedback('購物車是空的，無法結帳', 'error')
    return
  }
  if (hasUnavailableItem.value) {
    showFeedback('購物車有商品已下架或庫存不足，請先回購物車移除', 'error')
    return
  }
  if (!effectiveReceiver.value.address) {
    showFeedback('請填寫收件地址', 'error')
    return
  }
  showConfirm.value = true
}

async function handleCheckout() {
  submitting.value = true
  try {
    const receiver = effectiveReceiver.value
    const orderRes = await checkoutFromCart({
      couponCode: couponCode.value.trim() || null,
      receiverName: receiver.name || null,
      receiverPhone: receiver.phone || null,
      receiverAddress: receiver.address || null,
    }, idempotencyKey)
    const orderId = orderRes.data.orderId

    const formRes = await getEcpayCheckoutForm(orderId)
    cart.reset() // 訂單建立後後端已清空購物車，本地狀態跟著清空即可，不必再打一次 API

    // 從綠界返回 /orders 後，OrderListView 會輪詢這筆訂單的付款結果
    sessionStorage.setItem('pendingPaymentOrderId', String(orderId))
    submitEcpayForm(formRes.data)
    // 送出表單後瀏覽器即將離開本頁（導向綠界），故意不重設 submitting，避免使用者在轉導期間重複點擊
  } catch (err) {
    // 攔截器已正規化錯誤，訊息在 err.message；err.response 不存在（見 normalizeApiError）
    showConfirm.value = false
    showFeedback(err.message || '結帳失敗，請稍後再試', 'error')
    submitting.value = false
  }
}

onMounted(() => {
  cart.loadCart()
  loadMemberProfile()
})
</script>

<template>
  <div class="page-container">
    <h1 class="section-title">確認結帳</h1>

    <div v-if="loading" class="spinner"></div>

    <div v-else-if="items.length === 0" class="empty-state">
      <p>購物車是空的，無法結帳</p>
      <RouterLink to="/products" class="btn btn-accent">去逛逛</RouterLink>
    </div>

    <div v-else class="checkout-layout">
      <div class="checkout-form">
        <div class="form-card">
          <h3>訂單商品</h3>
          <div v-for="item in items" :key="item.cartItemId" class="order-item-row">
            <span class="order-item-name">
              {{ item.productName }} × {{ item.quantity }}
              <span v-if="item.available === false" class="item-flag danger">已下架 / 庫存不足</span>
              <span v-else-if="item.priceChanged" class="item-flag warn">
                價格已變動（加入時 NT$ {{ Number(item.priceWhenAdded).toLocaleString() }}）
              </span>
            </span>
            <span class="order-item-amount">NT$ {{ Number(item.subtotal).toLocaleString() }}</span>
          </div>
          <p v-if="hasUnavailableItem" class="checkout-blocker">
            購物車有商品已下架或庫存不足，請先
            <RouterLink to="/cart">回購物車</RouterLink>
            移除後再結帳。
          </p>
        </div>

        <div class="form-card">
          <h3>收件資訊<span class="req">＊</span></h3>

          <div v-if="hasProfileAddress" class="receiver-mode-select" role="group" aria-label="收件資訊來源">
            <button
              type="button"
              class="receiver-mode-option"
              :class="{ active: receiverMode === 'profile' }"
              :aria-pressed="receiverMode === 'profile'"
              @click="receiverMode = 'profile'"
            >
              <span class="receiver-mode-label">使用會員資料地址</span>
              <span class="receiver-mode-preview">{{ profileName }}　{{ profilePhone }}</span>
              <span class="receiver-mode-preview">{{ profileAddress }}</span>
            </button>
            <button
              type="button"
              class="receiver-mode-option"
              :class="{ active: receiverMode === 'custom' }"
              :aria-pressed="receiverMode === 'custom'"
              @click="receiverMode = 'custom'"
            >
              <span class="receiver-mode-label">填寫其他收件資訊</span>
              <span class="receiver-mode-preview">為這筆訂單指定不同的收件人</span>
            </button>
          </div>

          <div v-if="receiverMode === 'custom'" class="receiver-fields">
            <div class="field-group">
              <label>收件人姓名</label>
              <input v-model="receiverName" class="form-input" placeholder="選填" />
            </div>
            <div class="field-group">
              <label>收件人電話</label>
              <input v-model="receiverPhone" class="form-input" placeholder="選填" />
            </div>
            <div class="field-group">
              <label>收件地址<span class="req">＊</span></label>
              <div class="address-region-row">
                <select v-model="receiverCity" class="form-input region-select">
                  <option value="">縣市</option>
                  <option v-for="city in cityOptions" :key="city" :value="city">{{ city }}</option>
                </select>
                <select v-model="receiverDistrict" class="form-input region-select" :disabled="!receiverCity">
                  <option value="">行政區</option>
                  <option v-for="d in districtOptions" :key="d" :value="d">{{ d }}</option>
                </select>
              </div>
              <input
                v-model="receiverAddressDetail"
                class="form-input"
                placeholder="路名、巷弄、門牌號碼等詳細地址"
              />
            </div>
          </div>
        </div>

        <div class="form-card">
          <h3>優惠券（選填）</h3>
          <input v-model="couponCode" class="form-input" placeholder="輸入優惠券代碼" />
        </div>
      </div>

      <div class="checkout-summary">
        <h3 class="summary-title">付款金額</h3>
        <div class="summary-row">
          <span>商品小計</span>
          <span>NT$ {{ Number(totalAmount).toLocaleString() }}</span>
        </div>
        <div class="summary-divider"></div>
        <div class="summary-row total-row">
          <span>應付金額</span>
          <span class="total-price">NT$ {{ Number(totalAmount).toLocaleString() }}</span>
        </div>

        <div class="payment-method-row">
          <span class="payment-method-label">付款方式</span>
          <span class="payment-method-value">信用卡（綠界 ECPay）</span>
        </div>
        <p class="ecpay-note">目前僅支援信用卡付款，送出後將導向綠界測試付款頁輸入卡號完成付款</p>
        <button
          class="btn btn-accent submit-btn"
          :disabled="submitting || hasUnavailableItem"
          @click="openConfirm"
        >確認結帳並前往付款</button>
      </div>
    </div>

    <div v-if="showConfirm" class="modal-overlay" @click.self="!submitting && (showConfirm = false)">
      <div class="modal-content">
        <h3 class="modal-title">確認訂單內容</h3>

        <div class="confirm-section">
          <h4 class="confirm-section-title">訂單商品</h4>
          <div v-for="item in items" :key="item.cartItemId" class="order-item-row">
            <span class="order-item-name">{{ item.productName }} × {{ item.quantity }}</span>
            <span class="order-item-amount">NT$ {{ Number(item.subtotal).toLocaleString() }}</span>
          </div>
        </div>

        <div class="confirm-section">
          <h4 class="confirm-section-title">收件資訊</h4>
          <p class="confirm-text">{{ effectiveReceiver.name || '未填寫' }}　{{ effectiveReceiver.phone }}</p>
          <p class="confirm-text">{{ effectiveReceiver.address || '未填寫地址' }}</p>
        </div>

        <div v-if="couponCode.trim()" class="confirm-section">
          <h4 class="confirm-section-title">優惠券</h4>
          <p class="confirm-text">{{ couponCode.trim() }}（實際折扣將於送出後計算）</p>
        </div>

        <div class="confirm-total-row">
          <span>應付金額</span>
          <span class="total-price">NT$ {{ Number(totalAmount).toLocaleString() }}</span>
        </div>
        <p class="ecpay-note">確認無誤後將導向綠界測試付款頁，僅支援信用卡付款</p>

        <div class="modal-actions">
          <button class="btn btn-accent" type="button" :disabled="submitting" @click="handleCheckout">
            {{ submitting ? '處理中...' : '確認送出，前往付款' }}
          </button>
          <button class="btn btn-outline" type="button" :disabled="submitting" @click="showConfirm = false">
            返回修改
          </button>
        </div>
      </div>
    </div>

    <FeedbackToast :feedback="feedback" />
  </div>
</template>

<style scoped>
.checkout-layout {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 1.5rem;
  align-items: start;
  margin-top: 2rem;
}
.empty-state {
  text-align: center;
  padding: 4rem 2rem;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  color: var(--text-muted);
}
.checkout-form {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}
.form-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-section);
  padding: 1.25rem 1.5rem;
}
.form-card h3 {
  font-size: 0.95rem;
  font-weight: 700;
  margin-bottom: 1rem;
}
.req {
  color: #c92a2a;
  margin-left: 0.15rem;
}
.item-flag {
  display: inline-block;
  margin-left: 0.5rem;
  padding: 0.05rem 0.4rem;
  border-radius: 4px;
  font-size: 0.72rem;
  font-weight: 600;
  vertical-align: middle;
}
.item-flag.danger { background: #fde2e2; color: #c92a2a; }
.item-flag.warn { background: #fff3cd; color: #a06400; }
.checkout-blocker {
  margin-top: 0.75rem;
  font-size: 0.82rem;
  color: #c92a2a;
}
.checkout-blocker a { color: var(--accent-hover); font-weight: 600; }
.order-item-row {
  display: flex;
  justify-content: space-between;
  font-size: 0.88rem;
  color: var(--text-secondary);
  padding: 0.35rem 0;
}
.receiver-mode-select {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
  margin-bottom: 1rem;
}
.receiver-mode-option {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  text-align: left;
  padding: 0.75rem 0.9rem;
  border: 1.5px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-section);
  cursor: pointer;
  transition: var(--transition);
}
.receiver-mode-option:hover { border-color: var(--accent); }
.receiver-mode-option.active {
  border-color: var(--accent);
  background: var(--bg-soft);
}
.receiver-mode-label {
  font-size: 0.85rem;
  font-weight: 700;
  color: var(--text-primary);
}
.receiver-mode-option.active .receiver-mode-label { color: var(--accent-hover); }
.receiver-mode-preview {
  font-size: 0.78rem;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.receiver-fields {
  padding-top: 0.25rem;
}
.field-group {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  margin-bottom: 0.9rem;
}
.field-group:last-child { margin-bottom: 0; }
.field-group label {
  font-size: 0.82rem;
  color: var(--text-secondary);
}
.form-input {
  padding: 0.55rem 0.8rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.88rem;
}
.address-region-row {
  display: flex;
  gap: 0.6rem;
  margin-bottom: 0.6rem;
}
.region-select {
  flex: 1;
  min-width: 0;
  background: var(--bg-section);
}
.region-select:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.checkout-summary {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-section);
  padding: 1.5rem;
  position: sticky;
  top: 1.5rem;
}
.summary-title { font-size: 1.05rem; font-weight: 700; margin-bottom: 1.2rem; }
.summary-row {
  display: flex;
  justify-content: space-between;
  font-size: 0.9rem;
  margin-bottom: 0.75rem;
  color: var(--text-secondary);
}
.summary-divider { height: 1px; background: var(--border); margin: 1rem 0; }
.total-row { color: var(--text-primary); font-weight: 700; font-size: 1rem; }
.total-price { color: var(--accent-hover); font-size: 1.2rem; }
.payment-method-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 1rem;
  padding: 0.6rem 0.8rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  font-size: 0.85rem;
}
.payment-method-label { color: var(--text-secondary); }
.payment-method-value { font-weight: 700; color: var(--text-primary); }
.ecpay-note {
  font-size: 0.75rem;
  color: var(--text-muted);
  margin-top: 0.75rem;
  text-align: center;
}
.submit-btn { width: 100%; margin-top: 0.75rem; padding: 0.8rem; }

@media (max-width: 768px) {
  .checkout-layout { grid-template-columns: 1fr; }
  .receiver-mode-select { grid-template-columns: 1fr; }
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
  max-width: 440px;
  max-height: 85vh;
  overflow-y: auto;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-hover);
  padding: 1.75rem;
}
.modal-title {
  margin: 0 0 1.25rem;
  font-size: 1.15rem;
  color: var(--text-primary);
}
.confirm-section {
  margin-bottom: 1.1rem;
  padding-bottom: 1.1rem;
  border-bottom: 1px dashed var(--border);
}
.confirm-section-title {
  font-size: 0.8rem;
  font-weight: 700;
  color: var(--text-muted);
  margin-bottom: 0.4rem;
}
.confirm-text {
  font-size: 0.88rem;
  color: var(--text-secondary);
  margin: 0.15rem 0;
}
.confirm-total-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-weight: 700;
  font-size: 1rem;
  margin-top: 1rem;
}
.modal-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1.25rem;
}
.modal-actions .btn {
  flex: 1;
}
</style>
