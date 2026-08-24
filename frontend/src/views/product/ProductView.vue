<script setup>
import { ref, onMounted, watch, onUnmounted } from 'vue'
import { RouterLink } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useProductsStore } from '@/stores/products.js'
import { useCartStore } from '@/stores/cart.js'
import { useAuthStore } from '@/stores/auth.js'
import { useFeedback } from '@/composables/useFeedback.js'
import FeedbackToast from '@/components/FeedbackToast.vue'

const productsStore = useProductsStore()
const cart = useCartStore()
const auth = useAuthStore()

const { products, categories, totalElements, totalPages, loading } = storeToRefs(productsStore)

const keyword = ref('')
const selectedCategoryId = ref(null)
const page = ref(0)
const pageSize = 12

const { feedback, showFeedback } = useFeedback(2000)

async function fetchProducts() {
  await productsStore.loadProducts({
    // 分類 id 從 0 起算的資料庫也不會被誤判成「未選分類」；?? 只在 null/undefined 時才 fallback
    productCategoryId: selectedCategoryId.value ?? undefined,
    keyword: keyword.value.trim() || undefined,
    page: page.value,
    size: pageSize,
  })
}

function selectCategory(categoryId) {
  selectedCategoryId.value = categoryId
  page.value = 0
  fetchProducts()
}

let searchDebounceTimer = null

watch(keyword, () => {
  clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    page.value = 0
    fetchProducts()
  }, 400)
})

function changePage(n) {
  if (n < 0 || n >= totalPages.value) return
  page.value = n
  fetchProducts()
}

onUnmounted(() => {
  clearTimeout(searchDebounceTimer)
})

async function addToCart(product) {
  if (!auth.isLoggedIn) {
    showFeedback('請先登入會員後再加入購物車', 'error')
    return
  }
  const res = await cart.addItem(product.productId, 1)
  showFeedback(res.success ? '已加入購物車' : res.message, res.success ? 'success' : 'error')
}

onMounted(() => {
  // 兩個請求互不相依，平行送出可省一趟來回
  return Promise.all([productsStore.loadCategories(), fetchProducts()])
})
</script>

<template>
  <div class="page-container">
    <span class="section-tag">SHOP</span>
    <h2 class="section-title">商城商品</h2>

    <div class="toolbar">
      <input
        id="productSearch"
        v-model="keyword"
        class="keyword-input"
        placeholder="搜尋商品名稱..."
      />
    </div>

    <div class="category-filter">
      <button
        class="category-btn"
        :class="{ active: selectedCategoryId === null }"
        @click="selectCategory(null)"
      >全部</button>
      <button
        v-for="c in categories"
        :key="c.productCategoryId"
        class="category-btn"
        :class="{ active: selectedCategoryId === c.productCategoryId }"
        @click="selectCategory(c.productCategoryId)"
      >{{ c.name }}</button>
    </div>

    <div v-if="loading" class="spinner"></div>

    <div v-else-if="products.length === 0" class="empty-state">
      <p>找不到符合條件的商品</p>
    </div>

    <div v-else class="product-grid">
      <div v-for="p in products" :key="p.productId" class="product-card">
        <RouterLink :to="`/products/${p.productId}`" class="product-link">
          <div class="product-image">
            <img v-if="p.imageUrl" :src="p.imageUrl" :alt="p.name" />
            <span v-else class="product-image-fallback">{{ p.name?.[0] }}</span>
          </div>
          <h3 class="product-name">{{ p.name }}</h3>
          <p class="product-price">NT$ {{ Number(p.price).toLocaleString() }}</p>
          <p class="product-stock">庫存：{{ p.stock }}</p>
        </RouterLink>
        <button
          class="btn btn-accent add-btn"
          :disabled="p.stock === 0"
          @click="addToCart(p)"
        >{{ p.stock === 0 ? '已售完' : '加入購物車' }}</button>
      </div>
    </div>

    <div v-if="products.length > 0" class="result-info">共 {{ totalElements }} 件商品</div>

    <div v-if="totalPages > 1" class="pagination">
      <button class="page-btn" :disabled="page === 0" @click="changePage(page - 1)">‹</button>
      <span class="page-info">第 {{ page + 1 }} / {{ totalPages }} 頁</span>
      <button class="page-btn" :disabled="page >= totalPages - 1" @click="changePage(page + 1)">›</button>
    </div>

    <FeedbackToast :feedback="feedback" />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 0.75rem;
  margin-bottom: 1.25rem;
}
.keyword-input {
  flex: 1;
  max-width: 360px;
  padding: 0.6rem 1rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.9rem;
}
.category-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 2rem;
}
.category-btn {
  padding: 0.4rem 1rem;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--bg-section);
  color: var(--text-secondary);
  font-size: 0.85rem;
  cursor: pointer;
  transition: var(--transition);
}
.category-btn.active,
.category-btn:hover {
  border-color: var(--accent);
  color: var(--accent-hover);
}
.empty-state {
  text-align: center;
  padding: 4rem 0;
  color: var(--text-muted);
}
.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 1.25rem;
}
.product-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-section);
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.product-link {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.product-image {
  height: 140px;
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.product-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.product-image-fallback {
  font-size: 2.5rem;
  color: var(--text-muted);
}
.product-name {
  font-size: 0.95rem;
  font-weight: 700;
}
.product-price {
  color: var(--accent-hover);
  font-weight: 700;
}
.product-stock {
  font-size: 0.8rem;
  color: var(--text-muted);
}
.add-btn {
  width: 100%;
}
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 1rem;
  margin-top: 2rem;
}
.page-btn {
  padding: 0.4rem 0.9rem;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  background: var(--bg-section);
  cursor: pointer;
}
.page-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.page-info {
  font-size: 0.85rem;
  color: var(--text-secondary);
}
.result-info {
  font-size: 0.82rem;
  color: var(--text-muted);
  margin-top: 1rem;
  text-align: center;
}
</style>
