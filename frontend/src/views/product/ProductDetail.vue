<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useProductsStore } from '@/stores/products.js'
import { useCartStore } from '@/stores/cart.js'
import { useAuthStore } from '@/stores/auth.js'
import { useFeedback } from '@/composables/useFeedback.js'
import FeedbackToast from '@/components/FeedbackToast.vue'
import AppButton from '@/components/AppButton.vue'

import { formatImageUrl, useFallbackImage, DEFAULT_PRODUCT_IMAGE } from '@/utils/image.js'

const route = useRoute()
const router = useRouter()
const productsStore = useProductsStore()
const cart = useCartStore()
const auth = useAuthStore()

const { currentProduct: product, loading } = storeToRefs(productsStore)

const qty = ref(1)
// 按鈕的「已加入 ✓」與 toast 是同一次操作的兩個回饋，共用同一個時長才會同進同出
const FEEDBACK_MS = 2000
const { feedback, showFeedback } = useFeedback(FEEDBACK_MS)

const specLabels = {
  brand: '品牌',
  weight: '重量',
  flavor: '口味',
  size: '尺寸',
  color: '顏色',
  material: '材質',
  origin: '產地',
}

const specifications = computed(() => {
  if (!product.value) return {}
  const specs = {}
  for (const key of Object.keys(specLabels)) {
    const val = product.value[key]
    if (val !== null && val !== undefined && val !== '') {
      specs[key] = key === 'weight' ? `${val} kg` : val
    }
  }
  return specs
})

function increaseQty() {
  if (product.value && qty.value < product.value.stock) qty.value++
}
function decreaseQty() {
  if (qty.value > 1) qty.value--
}
// 手動輸入數量：夾在 1 與庫存量之間，非法輸入（空白、非整數）還原成夾好的上一個值
function handleQtyInput(event) {
  const value = Number(event.target.value)
  const max = product.value?.stock ?? 1
  if (!Number.isInteger(value)) {
    event.target.value = qty.value
    return
  }
  qty.value = Math.min(Math.max(value, 1), max)
}

// 加入成功後按鈕短暫顯示「已加入 ✓」，與 toast 同時結束
const justAdded = ref(false)
let justAddedTimer = null

async function addToCart() {
  if (!auth.isLoggedIn) {
    router.push({ name: 'login', query: { redirect: `/products/${route.params.id}` } })
    return
  }
  const res = await cart.addItem(product.value.productId, qty.value)
  showFeedback(res.success ? '已加入購物車' : res.message, res.success ? 'success' : 'error')
  if (!res.success) return
  justAdded.value = true
  clearTimeout(justAddedTimer)
  justAddedTimer = setTimeout(() => {
    justAdded.value = false
  }, FEEDBACK_MS)
}

// 用 watch(immediate) 而不是 onMounted：同一條路由只換 params（例如日後加「相關商品」
// 互連）時 Vue Router 會重用元件、onMounted 不會再觸發，畫面會停在舊商品。
watch(
  () => route.params.id,
  (id) => {
    if (id) productsStore.loadProductDetail(id)
    qty.value = 1
  },
  { immediate: true },
)

onUnmounted(() => clearTimeout(justAddedTimer))
</script>

<template>
  <div class="page-container" v-if="product">
    <div class="breadcrumb">
      <RouterLink to="/products">商城商品</RouterLink>
      <span>›</span>
      <span>{{ product.name }}</span>
    </div>

    <div class="detail-layout">
      <div class="product-image">
        <img v-if="product.imageUrl" :src="formatImageUrl(product.imageUrl)" :alt="product.name" @error="useFallbackImage($event, DEFAULT_PRODUCT_IMAGE)" />
        <span v-else class="product-image-fallback">{{ product.name?.[0] }}</span>
      </div>

      <div class="product-info">
        <h1 class="product-name">{{ product.name }}</h1>
        <p class="product-category" v-if="product.productCategoryName">分類：{{ product.productCategoryName }}</p>
        <p class="product-desc">{{ product.description }}</p>

        <div class="price-row">
          <span class="product-price">NT$ {{ Number(product.price).toLocaleString() }}</span>
          <span class="product-stock">庫存：{{ product.stock }}</span>
        </div>

        <dl v-if="Object.keys(specifications).length" class="spec-list">
          <template v-for="(value, key) in specifications" :key="key">
            <dt>{{ specLabels[key] || key }}</dt>
            <dd>{{ value }}</dd>
          </template>
        </dl>

        <div class="add-section">
          <div class="qty-ctrl">
            <button class="qty-btn" aria-label="減少數量" @click="decreaseQty">−</button>
            <input
              type="number"
              min="1"
              :max="product.stock"
              class="qty-input"
              aria-label="購買數量"
              :value="qty"
              @change="handleQtyInput"
            />
            <button class="qty-btn" aria-label="增加數量" @click="increaseQty">＋</button>
          </div>
          <AppButton
            variant="accent"
            class="add-btn"
            :class="{ 'add-btn--added': justAdded }"
            :disabled="product.stock === 0"
            @click="addToCart"
          >
            {{ product.stock === 0 ? '已售完' : justAdded ? '已加入 ✓' : '加入購物車' }}
          </AppButton>
        </div>
      </div>
    </div>

    <FeedbackToast :feedback="feedback" />
  </div>
  <div v-else-if="loading" class="spinner"></div>
</template>

<style scoped>
.add-btn {
  transition: background 0.2s ease, border-color 0.2s ease;
}

/* 加入成功後短暫轉綠，讓使用者不必移開視線就知道成功了 */
.add-btn--added {
  background: #2f9e44;
  border-color: #2f9e44;
}
.breadcrumb {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  color: var(--text-secondary);
  font-size: 0.85rem;
  margin-bottom: 1.5rem;
}
.detail-layout {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 2rem;
}
.product-image {
  height: 280px;
  border-radius: var(--radius-md);
  background: var(--bg-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.product-image img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.product-image-fallback {
  font-size: 4rem;
  color: var(--text-muted);
}
.product-name {
  font-size: 1.6rem;
  font-weight: 900;
  margin-bottom: 0.5rem;
}
.product-category {
  color: var(--text-muted);
  font-size: 0.85rem;
  margin-bottom: 0.75rem;
}
.product-desc {
  color: var(--text-secondary);
  margin-bottom: 1.5rem;
  line-height: 1.7;
}
.price-row {
  display: flex;
  align-items: baseline;
  gap: 1.5rem;
  margin-bottom: 1.5rem;
}
.product-price {
  font-size: 1.8rem;
  font-weight: 900;
  color: var(--accent-hover);
}
.product-stock {
  font-size: 0.85rem;
  color: var(--text-muted);
}
.spec-list {
  display: grid;
  grid-template-columns: 6rem 1fr;
  gap: 0.5rem 1rem;
  font-size: 0.88rem;
  border-top: 1px solid var(--border);
  border-bottom: 1px solid var(--border);
  padding: 1rem 0;
  margin-bottom: 1.5rem;
}
.spec-list dt {
  color: var(--text-secondary);
}
.spec-list dd {
  margin: 0;
  color: var(--text-primary);
}
.add-section {
  display: flex;
  align-items: center;
  gap: 1rem;
}
.qty-ctrl {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 0.3rem 0.6rem;
}
.qty-btn {
  background: none;
  border: none;
  font-size: 1.2rem;
  width: 28px;
  height: 28px;
  border-radius: 4px;
  transition: var(--transition);
}
.qty-btn:hover {
  background: var(--bg-soft);
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
@media (max-width: 768px) {
  .detail-layout { grid-template-columns: 1fr; }
}
</style>
