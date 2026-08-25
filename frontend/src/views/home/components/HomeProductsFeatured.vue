<template>
  <section class="section bg-soft" id="shop">
    <div class="page-container">
      <div class="section-header">
        <span class="section-tag">SHOP</span>
        <h2 class="section-title">健身商城精選</h2>
        <p class="section-desc">為您精選頂級運動補給品、訓練器材與防護週邊商品，加速您的健身成效。</p>
      </div>

      <!-- 商品分類快捷鍵 -->
      <div class="category-shortcuts">
        <button
          :class="['shortcut-btn', { active: activeCategoryId === null }]"
          @click="selectCategory(null)"
        >全部商品</button>
        <button
          v-for="cat in filteredCategories"
          :key="cat.productCategoryId"
          :class="['shortcut-btn', { active: activeCategoryId === cat.productCategoryId }]"
          @click="selectCategory(cat.productCategoryId)"
        >{{ cat.name }}</button>
      </div>

      <!-- 使用 v-show 保留舊卡片，切換分類不閃爍 -->
      <div v-show="loading && featuredProducts.length === 0" class="spinner"></div>
      <div v-show="!loading && featuredProducts.length === 0" class="empty-state">
        <p>目前尚無上架商品，請確認後端服務已啟動</p>
      </div>
      <div class="product-grid" :class="{ 'loading-fade': loading }" v-show="featuredProducts.length > 0">
        <ProductCard
          v-for="p in featuredProducts"
          :key="p.productId"
          :product="p"
        />
      </div>

      <div class="shop-cta" style="margin-top: 3rem;">
        <RouterLink
          :to="activeCategoryId ? `/products?category=${activeCategoryId}` : '/products'"
          class="btn btn-outline"
        >前往健身商城瀏覽全部 →</RouterLink>
      </div>
    </div>
  </section>
</template>

<script setup>
import { ref, onMounted, inject, computed } from 'vue'
import { RouterLink } from 'vue-router'
import { storeToRefs } from 'pinia'
import api from '@/api/axios.instance.js'
import { useAuthStore } from '@/stores/auth.js'
import { useCartStore } from '@/stores/cart.js'
import { useProductsStore } from '@/stores/products.js'
import ProductCard from '@/components/ProductCard.vue'

const auth = useAuthStore()
const cart = useCartStore()
const productsStore = useProductsStore()
// [自 Store 解構響應式屬性] categories
// - categories 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const { categories } = storeToRefs(productsStore)
const showToast = inject('showToast')

// [計算屬性] filteredCategories (filteredcategories)
// 為什麼要這麼做：依據指定屬性將不符合條件的陣列元素濾除，確保畫面只呈現使用者感興趣的資料。
const filteredCategories = computed(() => {
  return categories.value.filter(c => c.name !== '月卡訂閱')
})

// [響應式狀態] featuredProducts (featuredproducts)
// 為什麼要這麼做：在架商品清單，提供頁面渲染商品列表的資料來源。
const featuredProducts = ref([])
// [響應式狀態] loading (loading)
// 為什麼要這麼做：是否正在載入資料的布林標記，用以在請求完成前控制 Spinner 載入動畫的顯示。
const loading = ref(false)
// [響應式狀態] activeCategoryId (activecategoryid)
// 為什麼要這麼做：啟用狀態。
const activeCategoryId = ref(null)

// [功能方法] loadFeaturedProducts (載入featuredproducts)
// 為什麼要這麼做：向後端 API 請求最新數據，確保頁面載入或重新整理時，使用者能看到最即時且正確的資料。
async function loadFeaturedProducts() {
  loading.value = true
  try {
    const params = { page: 0, size: 4 }
    if (activeCategoryId.value !== null) {
      params.productCategoryId = activeCategoryId.value
    }
    const res = await api.get('/api/products', { params })
    featuredProducts.value = (res.data.content || []).map(p => ({
      ...p,
      id: p.productId || p.id
    }))
  } catch (e) {
    console.error('Failed to load featured products', e)
  } finally {
    loading.value = false
  }
}

// [功能方法] selectCategory (選擇category)
// 為什麼要這麼做：在列表中標記並記錄使用者目前點選的項目，供下一步的行為（如預約、結帳）做為對象。
function selectCategory(catId) {
  activeCategoryId.value = catId
  loadFeaturedProducts()
}


// [生命週期掛載] 組件初始化載入
// 為什麼要這麼做：在 Vue 元件完全掛載至網頁 DOM 後，立即向後端發送請求載入初始頁面資料，提供即時的視覺展現。
onMounted(async () => {
  await productsStore.loadCategories()
  loadFeaturedProducts()
})
</script>

<style src="../home.css"></style>

<style scoped>
.category-shortcuts {
  display: flex;
  justify-content: center;
  gap: 0.8rem;
  margin-bottom: 2.5rem;
  flex-wrap: wrap;
}

.shortcut-btn {
  padding: 0.55rem 1.4rem;
  border-radius: 24px;
  border: 1.5px solid var(--border);
  background: var(--bg-section);
  color: var(--text-secondary);
  cursor: pointer;
  font-family: var(--font-main);
  font-size: 0.9rem;
  font-weight: 600;
  transition: all 0.3s ease;
  box-shadow: var(--shadow-sm);
}

.shortcut-btn:hover {
  border-color: var(--primary);
  color: var(--primary);
  transform: translateY(-1px);
}

.shortcut-btn.active {
  background: var(--primary);
  color: #fff;
  border-color: var(--primary);
  box-shadow: 0 4px 12px rgba(44, 76, 62, 0.25);
}

/* 商品 Grid：自動滿版排列 */
.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 1.5rem;
  transition: opacity 0.25s ease;
}

.product-grid.loading-fade {
  opacity: 0.5;
  pointer-events: none;
}

@media (max-width: 1100px) {
  .product-grid { grid-template-columns: repeat(2, 1fr); }
}

@media (max-width: 600px) {
  .product-grid { grid-template-columns: 1fr; }
}
</style>

