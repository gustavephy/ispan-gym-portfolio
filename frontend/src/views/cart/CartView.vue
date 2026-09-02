<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useCartStore } from '@/stores/cart.js'
import { useAuthStore } from '@/stores/auth.js'
import { useFeedback } from '@/composables/useFeedback.js'
import { useMediaQuery } from '@/composables/useMediaQuery.js'
import { formatImageUrl, useFallbackImage, DEFAULT_PRODUCT_IMAGE } from '@/utils/image.js'
import FeedbackToast from '@/components/FeedbackToast.vue'
import EmptyState from '@/components/EmptyState.vue'
import OrderSummaryCard from '@/components/OrderSummaryCard.vue'

const cart = useCartStore()
const auth = useAuthStore()
const router = useRouter()
const isNarrow = useMediaQuery('(max-width: 768px)')

const { items, totalAmount, loading } = storeToRefs(cart)

const { feedback, showFeedback } = useFeedback(2000)

// 正在改數量/刪除的品項 cartItemId 集合：逐列上鎖（同一列連點會互相打架，
// 不同列可以同時操作），配合 store 的樂觀更新讓畫面即時反應
const busyIds = reactive(new Set())
const clearing = ref(false)
const removingUnavailable = ref(false)

const hasUnavailableItem = computed(() => items.value.some((i) => i.available === false))
const totalQuantity = computed(() => items.value.reduce((sum, i) => sum + Number(i.quantity), 0))

async function updateQuantity(item, newQty) {
  if (newQty < 1 || busyIds.has(item.cartItemId)) return
  // 有庫存資訊時前端先夾上限，不必等後端擋
  if (item.stock != null && newQty > item.stock) newQty = item.stock
  if (newQty === Number(item.quantity)) return
  busyIds.add(item.cartItemId)
  try {
    const res = await cart.updateItem(item.cartItemId, newQty)
    if (!res.success) showFeedback(res.message, 'error')
  } finally {
    busyIds.delete(item.cartItemId)
  }
}

function changeQty(item, delta) {
  updateQuantity(item, Number(item.quantity) + delta)
}

// 手動輸入數量：先把顯示值重置回目前數量（輸入框不直接綁定 store，等對帳完成
// store 重新載入才會反映新值），數字沒變或格式不合法就不送出請求
function handleQtyInput(item, event) {
  const newQty = Number(event.target.value)
  event.target.value = item.quantity
  if (!Number.isInteger(newQty) || newQty === Number(item.quantity)) return
  updateQuantity(item, newQty)
}

async function removeItem(item) {
  if (busyIds.has(item.cartItemId)) return
  busyIds.add(item.cartItemId)
  try {
    const res = await cart.removeItem(item.cartItemId)
    if (!res.success) showFeedback(res.message, 'error')
  } finally {
    busyIds.delete(item.cartItemId)
  }
}

async function removeUnavailable() {
  removingUnavailable.value = true
  try {
    const res = await cart.removeUnavailableItems()
    if (!res.success) showFeedback(res.message, 'error')
    else if (res.removed) showFeedback(`已移除 ${res.removed} 項失效商品`)
  } finally {
    removingUnavailable.value = false
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
    <div class="section-header">
      <span class="section-tag">CART</span>
      <h1 class="section-title">我的購物車</h1>
      <p class="section-desc">確認購物車內的商品與數量，確認無誤後即可前往結帳。</p>
    </div>

    <div v-if="loading" class="cart-layout" aria-hidden="true">
      <div class="cart-items">
        <div v-for="n in 3" :key="n" class="cart-item cart-item--skeleton">
          <div class="skeleton item-image"></div>
          <div class="item-info">
            <div class="skeleton skeleton-line" style="width: 55%"></div>
            <div class="skeleton skeleton-line skeleton-line--sm" style="width: 32%"></div>
          </div>
          <div class="skeleton skeleton-pill"></div>
          <div class="skeleton skeleton-line" style="width: 72px"></div>
        </div>
      </div>
      <div class="skeleton skeleton-summary"></div>
    </div>

    <EmptyState
      v-else-if="!auth.isLoggedIn"
      icon="cart"
      title="請先登入會員"
      description="登入後即可查看購物車與結帳"
    />

    <EmptyState
      v-else-if="items.length === 0"
      icon="cart"
      title="購物車是空的"
      description="快去挑選喜歡的商品，加入後就會出現在這裡"
    >
      <template #action>
        <RouterLink to="/products" class="btn btn-accent">去逛逛</RouterLink>
      </template>
    </EmptyState>

    <div v-else class="cart-layout">
      <div class="cart-items">
        <div v-if="hasUnavailableItem" class="unavailable-banner" role="alert">
          <span>部分商品已下架或庫存不足，結帳前需先移除。</span>
          <button
            type="button"
            class="btn btn-outline unavailable-banner__btn"
            :disabled="removingUnavailable"
            @click="removeUnavailable"
          >
            {{ removingUnavailable ? '移除中…' : '一鍵移除失效商品' }}
          </button>
        </div>

        <div
          v-for="item in items"
          :key="item.cartItemId"
          class="cart-item"
          :class="{ 'cart-item--unavailable': item.available === false }"
        >
          <div class="item-image">
            <img
              v-if="item.imageUrl"
              :src="formatImageUrl(item.imageUrl)"
              :alt="item.productName"
              @error="useFallbackImage($event, DEFAULT_PRODUCT_IMAGE)"
            />
            <span v-else class="item-image-fallback">{{ item.productName?.[0] }}</span>
          </div>

          <div class="item-info">
            <h3 class="item-name">{{ item.productName }}</h3>
            <p class="item-unit-price">NT$ {{ Number(item.unitPrice).toLocaleString() }} / 件</p>
            <p v-if="item.available === false" class="item-warn danger">
              此商品已下架或庫存不足（現有 {{ item.stock }}），結帳前請移除
            </p>
            <p v-else-if="item.priceChanged" class="item-warn">
              加入時 NT$ {{ Number(item.priceWhenAdded).toLocaleString() }}，現價已調整
            </p>
          </div>

          <div class="item-qty">
            <button
              class="qty-btn"
              :aria-label="`減少 ${item.productName} 數量`"
              :disabled="busyIds.has(item.cartItemId) || item.quantity <= 1"
              @click="changeQty(item, -1)"
            >
              −
            </button>
            <input
              type="number"
              min="1"
              class="qty-input"
              :aria-label="`${item.productName} 數量`"
              :value="item.quantity"
              :disabled="busyIds.has(item.cartItemId)"
              @change="handleQtyInput(item, $event)"
            />
            <button
              class="qty-btn"
              :aria-label="`增加 ${item.productName} 數量`"
              :disabled="
                busyIds.has(item.cartItemId) || (item.stock != null && item.quantity >= item.stock)
              "
              @click="changeQty(item, 1)"
            >
              ＋
            </button>
          </div>

          <div class="item-subtotal">NT$ {{ Number(item.subtotal).toLocaleString() }}</div>

          <button
            class="remove-btn"
            :aria-label="`移除 ${item.productName}`"
            :disabled="busyIds.has(item.cartItemId)"
            @click="removeItem(item)"
          >
            ✕
          </button>
        </div>

        <RouterLink to="/products" class="continue-shopping">← 繼續購物</RouterLink>
      </div>

      <OrderSummaryCard
        title="訂單摘要"
        :rows="[{ label: `商品小計（${totalQuantity} 件）`, value: Number(totalAmount) }]"
        total-label="合計"
        :total-value="Number(totalAmount)"
        sticky
      >
        <button v-if="!isNarrow" class="btn btn-accent checkout-btn" @click="goCheckout">
          前往結帳 →
        </button>
        <button class="btn btn-outline clear-btn" :disabled="clearing" @click="clearCart">
          {{ clearing ? '清空中…' : '清空購物車' }}
        </button>
      </OrderSummaryCard>
    </div>

    <div v-if="isNarrow && !loading && auth.isLoggedIn && items.length" class="mobile-checkout-bar">
      <div class="mobile-checkout-bar__total">
        <span>合計</span>
        <strong>NT$ {{ Number(totalAmount).toLocaleString() }}</strong>
      </div>
      <button class="btn btn-accent mobile-checkout-bar__btn" @click="goCheckout">
        前往結帳 →
      </button>
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
.item-info {
  flex: 1;
  min-width: 0;
}
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
.item-warn {
  font-size: 0.75rem;
  color: #a06400;
  margin-top: 0.2rem;
}
.item-warn.danger {
  color: #c92a2a;
}
.item-qty {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-shrink: 0;
}
.qty-btn {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 1px solid var(--border);
  background: var(--bg-section);
  cursor: pointer;
  font-size: 1rem;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: var(--transition);
}
.qty-btn:hover {
  background: var(--bg-soft);
}
.qty-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.qty-btn:focus-visible,
.remove-btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px rgba(201, 154, 91, 0.3);
}
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
.qty-input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.qty-input:focus-visible {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(201, 154, 91, 0.15);
}
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
.remove-btn:hover {
  color: #e03131;
}
.remove-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.checkout-btn {
  width: 100%;
  margin-top: 1rem;
  padding: 0.8rem;
}
.clear-btn {
  width: 100%;
  margin-top: 0.6rem;
  font-size: 0.85rem;
}

.unavailable-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-bottom: 1rem;
  padding: 0.8rem 1.1rem;
  border: 1px solid #f1c6c6;
  border-radius: var(--radius-md);
  background: #fdecec;
  font-size: 0.85rem;
  color: #c92a2a;
}
.unavailable-banner__btn {
  font-size: 0.8rem;
  padding: 0.4rem 0.9rem;
  white-space: nowrap;
}
.cart-item--unavailable {
  border-color: #f1c6c6;
  background: #fdf4f4;
}

.continue-shopping {
  display: inline-block;
  margin-top: 0.25rem;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--accent-hover);
}
.continue-shopping:hover {
  text-decoration: underline;
}

/* 手機版底部固定結帳條 */
.mobile-checkout-bar {
  display: none;
}

/* 載入骨架：沿用 cart-item / cart-layout 版位，載入完成後不跳版 */
.cart-item--skeleton {
  pointer-events: none;
}
.skeleton-line {
  height: 0.85rem;
  border-radius: 4px;
}
.skeleton-line--sm {
  height: 0.7rem;
  margin-top: 0.5rem;
}
.skeleton-pill {
  width: 96px;
  height: 28px;
  border-radius: 999px;
  flex-shrink: 0;
}
.skeleton-summary {
  height: 232px;
  border-radius: var(--radius-md);
}

@media (max-width: 768px) {
  .cart-layout {
    grid-template-columns: 1fr;
  }
  .skeleton-summary {
    height: 180px;
  }
  /* 底部已有固定結帳條，摘要卡內就不再重複一顆主 CTA */
  .checkout-btn {
    display: none;
  }
  .clear-btn {
    margin-top: 0;
  }
  /* 內容不要被底部固定條蓋住 */
  .page-container {
    padding-bottom: 5.5rem;
  }
  .mobile-checkout-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 90;
    padding: 0.75rem 1.1rem calc(0.75rem + env(safe-area-inset-bottom, 0px));
    background: var(--bg-section);
    border-top: 1px solid var(--border);
    box-shadow: 0 -4px 16px rgba(0, 0, 0, 0.08);
  }
  .mobile-checkout-bar__total {
    display: flex;
    flex-direction: column;
    font-size: 0.72rem;
    color: var(--text-muted);
  }
  .mobile-checkout-bar__total strong {
    font-size: 1.05rem;
    color: var(--accent-hover);
  }
  .mobile-checkout-bar__btn {
    flex-shrink: 0;
    padding: 0.7rem 1.4rem;
  }
}
</style>
