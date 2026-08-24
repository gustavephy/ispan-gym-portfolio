<script setup>
import { ref, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useCartStore } from '@/stores/cart.js'
import { checkoutFromCart } from '@/api/order.js'
import { getEcpayCheckoutForm } from '@/api/payment.js'
import { submitEcpayForm } from '@/utils/ecpay.js'
import { useFeedback } from '@/composables/useFeedback.js'
import FeedbackToast from '@/components/FeedbackToast.vue'

const cart = useCartStore()
const { items, totalAmount, loading } = storeToRefs(cart)

const couponCode = ref('')
const receiverName = ref('')
const receiverPhone = ref('')
const receiverAddress = ref('')
const submitting = ref(false)

// 這次結帳的冪等金鑰：整個頁面存續期間固定不變，手滑連點「確認結帳」
// 只會真正建立一次訂單；離開頁面重新進來（新的一次結帳）才會換一把新的
const idempotencyKey = crypto.randomUUID()

const { feedback, showFeedback } = useFeedback()

async function handleCheckout() {
  if (items.value.length === 0) {
    showFeedback('購物車是空的，無法結帳', 'error')
    return
  }

  submitting.value = true
  try {
    const orderRes = await checkoutFromCart({
      couponCode: couponCode.value.trim() || null,
      receiverName: receiverName.value.trim() || null,
      receiverPhone: receiverPhone.value.trim() || null,
      receiverAddress: receiverAddress.value.trim() || null,
    }, idempotencyKey)
    const orderId = orderRes.data.orderId

    const formRes = await getEcpayCheckoutForm(orderId)
    cart.reset() // 訂單建立後後端已清空購物車，本地狀態跟著清空即可，不必再打一次 API

    submitEcpayForm(formRes.data)
    // 送出表單後瀏覽器即將離開本頁（導向綠界），故意不重設 submitting，避免使用者在轉導期間重複點擊
  } catch (err) {
    // 攔截器已正規化錯誤，訊息在 err.message；err.response 不存在（見 normalizeApiError）
    showFeedback(err.message || '結帳失敗，請稍後再試', 'error')
    submitting.value = false
  }
}

onMounted(() => {
  cart.loadCart()
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
            <span class="order-item-name">{{ item.productName }} × {{ item.quantity }}</span>
            <span class="order-item-amount">NT$ {{ Number(item.subtotal).toLocaleString() }}</span>
          </div>
        </div>

        <div class="form-card">
          <h3>收件資訊（選填）</h3>
          <div class="field-group">
            <label>收件人姓名</label>
            <input v-model="receiverName" class="form-input" placeholder="選填" />
          </div>
          <div class="field-group">
            <label>收件人電話</label>
            <input v-model="receiverPhone" class="form-input" placeholder="選填" />
          </div>
          <div class="field-group">
            <label>收件地址</label>
            <input v-model="receiverAddress" class="form-input" placeholder="選填" />
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
        <p class="ecpay-note">將導向綠界 ECPay 測試付款頁完成付款</p>
        <button
          class="btn btn-accent submit-btn"
          :disabled="submitting"
          @click="handleCheckout"
        >{{ submitting ? '處理中...' : '確認結帳並前往付款' }}</button>
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
.order-item-row {
  display: flex;
  justify-content: space-between;
  font-size: 0.88rem;
  color: var(--text-secondary);
  padding: 0.35rem 0;
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
.ecpay-note {
  font-size: 0.75rem;
  color: var(--text-muted);
  margin-top: 0.75rem;
  text-align: center;
}
.submit-btn { width: 100%; margin-top: 0.75rem; padding: 0.8rem; }

@media (max-width: 768px) {
  .checkout-layout { grid-template-columns: 1fr; }
}
</style>
