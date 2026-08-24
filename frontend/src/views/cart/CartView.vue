<script setup>
import { ref, onMounted } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useCartStore } from '@/stores/cart.js'
import { useAuthStore } from '@/stores/auth.js'
import { useFeedback } from '@/composables/useFeedback.js'
import FeedbackToast from '@/components/FeedbackToast.vue'

const cart = useCartStore()
const auth = useAuthStore()
const router = useRouter()

const { items, totalAmount, loading } = storeToRefs(cart)

const { feedback, showFeedback } = useFeedback(2000)

// 目前正在改數量/刪除的品項 cartItemId；期間鎖住該列按鈕與輸入框，
// 避免連續快速點擊時同一品項送出好幾個重疊的請求互相打架
const busyId = ref(null)
const clearing = ref(false)

async function updateQuantity(item, newQty) {
  if (newQty < 1 || busyId.value) return
  busyId.value = item.cartItemId
  try {
    const res = await cart.updateItem(item.cartItemId, newQty)
    if (!res.success) showFeedback(res.message, 'error')
  } finally {
    busyId.value = null
  }
}

function changeQty(item, delta) {
  updateQuantity(item, Number(item.quantity) + delta)
}

// 手動輸入數量：先把顯示值重置回目前數量（輸入框不直接綁定 store，等後端確認成功
// 後 store 重新載入才會反映新值），數字沒變或格式不合法就不送出請求
function handleQtyInput(item, event) {
  const newQty = Number(event.target.value)
  event.target.value = item.quantity
  if (!Number.isInteger(newQty) || newQty === item.quantity) return
  updateQuantity(item, newQty)
}

async function removeItem(item) {
  if (busyId.value) return
  busyId.value = item.cartItemId
  try {
    const res = await cart.removeItem(item.cartItemId)
    if (!res.success) showFeedback(res.message, 'error')
  } finally {
    busyId.value = null
  }
}

async function clearCart() {
  clearing.value = true
  try {
    const res = await cart.clearCart()
    if (!res.success) showFeedback(res.message, 'error')
  } finally {
    clearing.value = false
  }
}

function goCheckout() {
  router.push('/checkout')
}

onMounted(() => {
  cart.loadCart()
})
</script>

<template>
  <div class="page-container">
    <h1 class="section-title">我的購物車</h1>

    <div v-if="loading" class="spinner"></div>

    <div v-else-if="!auth.isLoggedIn" class="empty-state">
      <p>請先登入會員才能查看購物車</p>
    </div>

    <div v-else-if="items.length === 0" class="empty-state">
      <p>購物車是空的</p>
      <RouterLink to="/products" class="btn btn-accent">去逛逛</RouterLink>
    </div>

    <div v-else class="cart-layout">
      <div class="cart-items">
        <div v-for="item in items" :key="item.cartItemId" class="cart-item">
          <div class="item-image">
            <img v-if="item.imageUrl" :src="item.imageUrl" :alt="item.productName" />
            <span v-else class="item-image-fallback">{{ item.productName?.[0] }}</span>
          </div>

          <div class="item-info">
            <h3 class="item-name">{{ item.productName }}</h3>
            <p class="item-unit-price">NT$ {{ Number(item.unitPrice).toLocaleString() }} / 件</p>
          </div>

          <div class="item-qty">
            <button
              class="qty-btn"
              :aria-label="`減少 ${item.productName} 數量`"
              :disabled="busyId === item.cartItemId"
              @click="changeQty(item, -1)"
            >−</button>
            <input
              type="number"
              min="1"
              class="qty-input"
              :aria-label="`${item.productName} 數量`"
              :value="item.quantity"
              :disabled="busyId === item.cartItemId"
              @change="handleQtyInput(item, $event)"
            />
            <button
              class="qty-btn"
              :aria-label="`增加 ${item.productName} 數量`"
              :disabled="busyId === item.cartItemId"
              @click="changeQty(item, 1)"
            >＋</button>
          </div>

          <div class="item-subtotal">NT$ {{ Number(item.subtotal).toLocaleString() }}</div>

          <button
            class="remove-btn"
            :aria-label="`移除 ${item.productName}`"
            :disabled="busyId === item.cartItemId"
            @click="removeItem(item)"
          >✕</button>
        </div>
      </div>

      <div class="cart-summary">
        <h3 class="summary-title">訂單摘要</h3>
        <div class="summary-row">
          <span>商品小計</span>
          <span>NT$ {{ Number(totalAmount).toLocaleString() }}</span>
        </div>
        <div class="summary-divider"></div>
        <div class="summary-row total-row">
          <span>合計</span>
          <span class="total-price">NT$ {{ Number(totalAmount).toLocaleString() }}</span>
        </div>

        <button class="btn btn-accent checkout-btn" @click="goCheckout">前往結帳 →</button>
        <button class="btn btn-outline clear-btn" :disabled="clearing" @click="clearCart">
          {{ clearing ? '清空中…' : '清空購物車' }}
        </button>
      </div>
    </div>

    <FeedbackToast :feedback="feedback" />
  </div>
</template>

<style scoped>
.cart-layout {
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
.cart-item {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 1rem;
  padding: 1rem 1.2rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-section);
}
.item-image {
  width: 64px;
  height: 64px;
  flex-shrink: 0;
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.item-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.item-image-fallback {
  font-size: 1.5rem;
  color: var(--text-muted);
}
.item-info { flex: 1; min-width: 0; }
.item-name {
  font-size: 0.95rem;
  margin-bottom: 0.3rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.item-unit-price {
  font-size: 0.8rem;
  color: var(--text-muted);
}
.item-qty { display: flex; align-items: center; gap: 0.5rem; flex-shrink: 0; }
.qty-btn {
  width: 28px; height: 28px;
  border-radius: 50%;
  border: 1px solid var(--border);
  background: var(--bg-section);
  cursor: pointer;
  font-size: 1rem;
  display: flex; align-items: center; justify-content: center;
  transition: var(--transition);
}
.qty-btn:hover { background: var(--bg-soft); }
.qty-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.qty-input {
  width: 44px;
  text-align: center;
  font-weight: 700;
  font-size: 0.95rem;
  color: var(--text-primary);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 0.3rem 0.2rem;
  /* 隱藏瀏覽器預設的上下微調箭頭，跟旁邊的 +/- 按鈕重複 */
  appearance: textfield;
  -moz-appearance: textfield;
}
.qty-input::-webkit-outer-spin-button,
.qty-input::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}
.qty-input:disabled { opacity: 0.5; cursor: not-allowed; }
.item-subtotal {
  width: 100px;
  text-align: right;
  font-weight: 700;
  color: var(--accent-hover);
  flex-shrink: 0;
}
.remove-btn {
  background: none;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 1rem;
  padding: 0.3rem;
  transition: var(--transition);
  flex-shrink: 0;
}
.remove-btn:hover { color: #e03131; }
.remove-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.cart-summary {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-section);
  padding: 1.5rem;
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
.checkout-btn { width: 100%; margin-top: 1rem; padding: 0.8rem; }
.clear-btn { width: 100%; margin-top: 0.6rem; font-size: 0.85rem; }

@media (max-width: 768px) {
  .cart-layout { grid-template-columns: 1fr; }
}
</style>
