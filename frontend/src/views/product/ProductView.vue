<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useProductsStore } from '@/stores/products.js'
import ProductCard from '@/components/ProductCard.vue'
import AppPagination from '@/components/AppPagination.vue'

const productsStore = useProductsStore()
const route = useRoute()
const router = useRouter()

// [自 Store 解構響應式屬性] products, categories, loading
const { products, categories, loading } = storeToRefs(productsStore)

// ---- 狀態 ----
// [響應式狀態] page (page)
const page = ref(1)
const itemsPerPage = ref(12)

// [響應式狀態] selectedCategoryKey (selectedcategorykey)
// 為什麼要這麼做：在列表中標記並記錄使用者目前點選的項目，供下一步的行為（如預約、結帳）做為對象。
const selectedCategoryKey = ref('') // '' = 全部, '數字' = 分類ID
// [響應式狀態] searchParams (搜尋params)
// 為什麼要這麼做：根據使用者輸入的關鍵字或篩選條件，重新過濾出符合條件的清單，提供快速檢索功能。
const searchParams = ref({ keyword: '' })
// [響應式狀態] priceSort (pricesort)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const priceSort = ref('default')
// [響應式狀態] minPrice (minprice)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const minPrice = ref(null)
// [響應式狀態] maxPrice (maxprice)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const maxPrice = ref(null)
// [響應式狀態] minWeight (minweight)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const minWeight = ref(null)
// [響應式狀態] maxWeight (maxweight)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const maxWeight = ref(null)
// [響應式狀態] selectedBrands (selectedbrands)
// 為什麼要這麼做：在列表中標記並記錄使用者目前點選的項目，供下一步的行為（如預約、結帳）做為對象。
const selectedBrands = ref([])

// [響應式狀態] collapsedWidgets (collapsedwidgets)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const collapsedWidgets = ref({})
// [功能方法] toggleWidget (切換widget)
// 為什麼要這麼做：向伺服器查詢並獲取指定資料，以供畫面渲染或後續邏輯判斷使用。
function toggleWidget(key) {
  collapsedWidgets.value[key] = !collapsedWidgets.value[key]
}

const quickWeightRanges = [
  { label: '0 - 10 kg', min: 0, max: 10 },
  { label: '10 - 20 kg', min: 10, max: 20 },
  { label: '20 kg 以上', min: 20, max: null }
]

// [功能方法] isQuickWeightActive (isquickweightactive)
// 為什麼要這麼做：啟用狀態。
function isQuickWeightActive(range) {
  return minWeight.value === range.min && maxWeight.value === range.max
}

// [功能方法] applyQuickWeight (套用quickweight)
// 為什麼要這麼做：將選定的優惠券、折扣或設定套用至目前的訂單或畫面，即時計算最新折抵數值。
function applyQuickWeight(range) {
  if (isQuickWeightActive(range)) {
    minWeight.value = null
    maxWeight.value = null
  } else {
    minWeight.value = range.min
    maxWeight.value = range.max
  }
}

const sortOptions = [
  { value: 'default', label: '預設排序' },
  { value: 'lowToHigh', label: '價格由低到高' },
  { value: 'highToLow', label: '價格由高到低' }
]



// [功能方法] getCategoryProducts (取得categoryproducts)
// 為什麼要這麼做：向伺服器查詢並獲取指定資料，以供畫面渲染或後續邏輯判斷使用。
function getCategoryProducts(catKey, limit = 4) {
  let list = products.value.slice()
  // 排除月卡訂閱與課程
  list = list.filter(p => p.type !== 'SUBSCRIPTION' && p.type !== 'COURSE')
  list = list.filter(p => String(p.productCategoryId) === catKey)
  return list.slice(0, limit)
}

// [功能方法] productCountByCategory (productcountbycategory)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
function productCountByCategory(catId) {
  return products.value.filter(p => String(p.productCategoryId) === String(catId)).length
}

// [計算屬性] currentCategoryLabel (currentcategorylabel)
// 為什麼要這麼做：目前選取的資料，以便進行單一資料展示、編輯或預約操作。
const currentCategoryLabel = computed(() => {
  const cat = categories.value.find(c => String(c.productCategoryId) === selectedCategoryKey.value)
  return cat?.name || '商品列表'
})

// [計算屬性] availableBrands (availablebrands)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const availableBrands = computed(() => {
  const inCat = products.value.filter(p => {
    return String(p.productCategoryId) === selectedCategoryKey.value
  })
  return [...new Set(inCat.map(p => p.brand).filter(Boolean))]
})


// ---- Computed ----
const totalItems = computed(() => productsStore.totalElements)
const totalPages = computed(() => productsStore.totalPages)
const paginatedProducts = computed(() => productsStore.products)



// ---- 方法 ----
// [功能方法] enterCategory (entercategory)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
function enterCategory(key) {
  resetFilters()
  router.push({ query: { ...route.query, category: key } })
}

// [功能方法] exitCategory (exitcategory)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
function exitCategory() {
  resetFilters()
  const query = { ...route.query }
  delete query.category
  router.push({ query })
}

// [功能方法] resetFilters (重置filters)
// 為什麼要這麼做：將所有篩選條件、分頁或表單狀態還原至預設初值，解決狀態殘留引起的查詢問題。
function resetFilters() {
  searchParams.value.keyword = ''
  priceSort.value = 'default'
  minPrice.value = null
  maxPrice.value = null
  minWeight.value = null
  maxWeight.value = null
  selectedBrands.value = []
  page.value = 1
}

// [功能方法] toggleBrand (切換brand)
// 為什麼要這麼做：切換布林值狀態（例如展開/收合、明文/密碼），以最直覺的方式改變介面的視覺呈現。
function toggleBrand(brand) {
  const idx = selectedBrands.value.indexOf(brand)
  if (idx > -1) selectedBrands.value.splice(idx, 1)
  else selectedBrands.value.push(brand)
}



// 後端商品載入方法
async function fetchProducts() {
  await productsStore.loadProducts({
    page: page.value - 1,
    size: selectedCategoryKey.value ? itemsPerPage.value : 100,
    productCategoryId: selectedCategoryKey.value ? Number(selectedCategoryKey.value) : null,
    keyword: searchParams.value.keyword || '',
    brand: selectedBrands.value.join(',') || undefined,
    minPrice: minPrice.value || null,
    maxPrice: maxPrice.value || null,
    minWeight: minWeight.value !== null ? minWeight.value : undefined,
    maxWeight: maxWeight.value !== null ? maxWeight.value : undefined,
    sort: priceSort.value !== 'default' ? priceSort.value : undefined,
  })
}

// 監聽所有篩選與每頁數量變更，重置頁碼並重新載入
watch([selectedCategoryKey, selectedBrands, priceSort, minPrice, maxPrice, minWeight, maxWeight], () => {
  page.value = 1
  fetchProducts()
}, { deep: true })

// 監聽關鍵字輸入變更（不帶 page=0 重置，改由 doGlobalSearch 手動觸發或 debounce 處理較佳，但此處維持變動即載入）
watch(() => searchParams.value.keyword, () => {
  page.value = 1
  fetchProducts()
})

// 監聽頁碼變更，重新載入
watch(page, () => {
  fetchProducts()
})

// [功能方法] applyRouteQuery (套用route查詢)
// 為什麼要這麼做：發送多重篩選條件的查詢請求給後端，並將回傳結果渲染於畫面上。
function applyRouteQuery(query) {
  const categoryFromQuery = Number(query.category)
  if (Number.isInteger(categoryFromQuery) && categoryFromQuery > 0) {
    selectedCategoryKey.value = String(categoryFromQuery)
  } else {
    selectedCategoryKey.value = ''
  }
  if (query.search) {
    searchParams.value.keyword = query.search
  } else {
    searchParams.value.keyword = ''
  }
}

// [監聽器] 監聽特定狀態變化
// 為什麼要這麼做：當監聽的數據或屬性發生異動時，即時觸發與其關聯的連動業務邏輯、重新計算數值或重新載入資料。
watch(() => route.query, (newQuery) => {
  applyRouteQuery(newQuery)
  fetchProducts()
}, { immediate: false })

// [生命週期掛載] 組件初始化載入
// 為什麼要這麼做：在 Vue 元件完全掛載至網頁 DOM 後，立即向後端發送請求載入初始頁面資料，提供即時的視覺展現。
onMounted(async () => {
  await productsStore.loadCategories()
  applyRouteQuery(route.query)
  await fetchProducts()


  // 若從其他連結點擊攜帶 productId，自動將網頁平滑捲動至該商品卡片位置
  if (route.query.productId) {
    const targetId = 'product-card-' + route.query.productId
    let attempts = 0
    const interval = setInterval(() => {
      const el = document.getElementById(targetId)
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'center' })
        clearInterval(interval)
      }
      attempts++
      if (attempts > 30) clearInterval(interval) // 最多檢查 3 秒
    }, 100)
  }
})
</script>

<template>
  <div class="page-container">

    <!-- ============================
         模式 1：分類總覽（首頁）
         ============================ -->
    <div v-if="!selectedCategoryKey">
      <div class="section-header">
        <span class="section-tag">SHOP</span>
        <h2 class="section-title">健身商城精選</h2>
        <p class="section-desc">為您精選頂級運動補給品、訓練器材與防護週邊商品，加速您的健身成效。</p>
      </div>

      <!-- 全域搜尋 -->
      <div class="global-search-bar">
        <span class="global-search-icon">🔍</span>
        <input
          v-model="searchParams.keyword"
          class="global-search-inp"
          placeholder="搜尋商品名稱..."
          @keyup.enter="doGlobalSearch"
        />
        <button class="global-search-btn" @click="doGlobalSearch">搜尋</button>
      </div>

      <!-- 首頁分類展示商品 -->
      <div class="homepage-categories">
        <!-- 各實體分類 Sections -->
        <div 
          v-for="cat in categories" 
          :key="cat.productCategoryId"
          class="category-block"
        >
          <div class="block-header" v-if="getCategoryProducts(String(cat.productCategoryId)).length > 0">
            <h3 class="block-title">📦 {{ cat.name }}</h3>
            <button class="block-more-btn" @click="enterCategory(String(cat.productCategoryId))">更多此類別商品 ›</button>
          </div>
          <div class="product-grid home-grid" v-if="getCategoryProducts(String(cat.productCategoryId)).length > 0">
            <ProductCard
              v-for="p in getCategoryProducts(String(cat.productCategoryId))"
              :key="p.id || p.productId"
              :product="p"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- ============================
         模式 2：分類內頁
         ============================ -->
    <div v-else class="category-page">
      <!-- 麵包屑 breadcrumb -->
      <div class="breadcrumb">
        <button class="bc-home" @click="exitCategory">健身商城</button>
        <span class="bc-sep">›</span>
        <button v-if="selectedBrands.length > 0" class="bc-home" @click="selectedBrands = []">{{ currentCategoryLabel }}</button>
        <span v-else class="bc-current">{{ currentCategoryLabel }}</span>
        <template v-if="selectedBrands.length > 0">
          <span class="bc-sep">›</span>
          <span class="bc-current">{{ selectedBrands.join(', ') }}</span>
        </template>
      </div>

      <div class="shop-layout">
        <!-- ===== 左側側邊欄（分類內搜尋、排序、價格、重量與品牌篩選面板） ===== -->
        <aside class="shop-sidebar">

          <!-- 分類內搜尋 -->
          <div class="sidebar-search">
            <label class="widget-title" style="display: block; margin-bottom: 0.4rem;">分類內搜尋</label>
            <input
              v-model="searchParams.keyword"
              class="sidebar-search-inp"
              placeholder="在此分類中搜尋..."
            />
          </div>

          <!-- 排序與價格區間 (與類別篩選合一) -->
          <div class="sidebar-widget">
            <h3 class="widget-title collapsible-header" @click="toggleWidget('sort')">
              排序與價格
              <span class="collapse-arrow">{{ collapsedWidgets['sort'] ? '▼' : '▲' }}</span>
            </h3>
            <div v-show="!collapsedWidgets['sort']">
              <div class="widget-nav-list">
                <button
                  v-for="opt in sortOptions"
                  :key="opt.value"
                  :class="['nav-item', { active: priceSort === opt.value }]"
                  @click="priceSort = opt.value"
                >
                  <span class="nav-check">{{ priceSort === opt.value ? '●' : '○' }}</span>
                  {{ opt.label }}
                </button>
              </div>
              <div class="price-range-row">
                <input type="number" v-model.number="minPrice" placeholder="最低" class="price-range-inp" />
                <span class="price-range-sep">~</span>
                <input type="number" v-model.number="maxPrice" placeholder="最高" class="price-range-inp" />
              </div>
            </div>
          </div>

          <!-- 重量區間篩選 -->
          <div class="sidebar-widget">
            <h3 class="widget-title collapsible-header" @click="toggleWidget('weight')">
              重量區間
              <span class="collapse-arrow">{{ collapsedWidgets['weight'] ? '▼' : '▲' }}</span>
            </h3>
            <div v-show="!collapsedWidgets['weight']">
              <!-- 快速區間篩選 -->
              <div class="widget-nav-list" style="margin-bottom: 0.6rem;">
                <button
                  v-for="range in quickWeightRanges"
                  :key="range.label"
                  :class="['nav-item', { active: isQuickWeightActive(range) }]"
                  @click="applyQuickWeight(range)"
                >
                  <span class="nav-check">{{ isQuickWeightActive(range) ? '●' : '○' }}</span>
                  {{ range.label }}
                </button>
              </div>
              <!-- 自行輸入 -->
              <div class="price-range-row">
                <input type="number" step="0.1" v-model.number="minWeight" placeholder="最輕 (kg)" class="price-range-inp" />
                <span class="price-range-sep">~</span>
                <input type="number" step="0.1" v-model.number="maxWeight" placeholder="最重 (kg)" class="price-range-inp" />
              </div>
            </div>
          </div>

          <!-- 品牌篩選 -->
          <div v-if="availableBrands.length > 0" class="sidebar-widget">
            <h3 class="widget-title collapsible-header" @click="toggleWidget('brand')">
              品牌
              <span class="collapse-arrow">{{ collapsedWidgets['brand'] ? '▼' : '▲' }}</span>
            </h3>
            <div v-show="!collapsedWidgets['brand']" class="widget-nav-list">
              <button
                v-for="brand in availableBrands"
                :key="brand"
                :class="['nav-item', { active: selectedBrands.includes(brand) }]"
                @click="toggleBrand(brand)"
              >
                <span class="nav-check">{{ selectedBrands.includes(brand) ? '■' : '□' }}</span>
                {{ brand }}
              </button>
            </div>
          </div>

          <!-- 重置按鈕 -->
          <button class="sidebar-reset-btn" @click="resetFilters">重置篩選條件</button>
        </aside>

        <!-- ===== 右側商品區 ===== -->
        <main class="shop-main">
          <!-- 結果資訊與篩選標籤 -->
          <div class="result-bar">
            <div class="result-left">
              <span class="result-count">共 {{ totalItems }} 件商品</span>
            </div>
            <div class="active-tags">
              <span v-if="searchParams.keyword" class="active-tag">
                關鍵字：{{ searchParams.keyword }}
                <button @click="searchParams.keyword = ''" class="tag-remove">✕</button>
              </span>

              <span v-if="minWeight !== null && minWeight !== ''" class="active-tag">
                重量 ≥ {{ minWeight }} kg
                <button @click="minWeight = null" class="tag-remove">✕</button>
              </span>
              <span v-if="maxWeight !== null && maxWeight !== ''" class="active-tag">
                重量 ≤ {{ maxWeight }} kg
                <button @click="maxWeight = null" class="tag-remove">✕</button>
              </span>

              <span v-for="brand in selectedBrands" :key="brand" class="active-tag">
                {{ brand }}
                <button @click="toggleBrand(brand)" class="tag-remove">✕</button>
              </span>
              <span v-for="sku in selectedSkus" :key="sku" class="active-tag">
                規格：{{ sku }}
                <button @click="toggleSku(sku)" class="tag-remove">✕</button>
              </span>
              <template v-for="(vals, key) in selectedSpecs" :key="key">
                <span v-for="val in vals" :key="val" class="active-tag">
                  {{ val }}
                  <button @click="toggleSpec(key, val)" class="tag-remove">✕</button>
                </span>
              </template>
            </div>
          </div>

          <div v-if="loading" class="spinner"></div>
          <div v-else-if="paginatedProducts.length === 0" class="empty-state">
            <p>🔍 找不到符合的商品，請調整篩選條件</p>
          </div>

          <div class="product-grid" v-else>
            <ProductCard
              v-for="p in paginatedProducts"
              :key="p.id || p.productId"
              :id="'product-card-' + (p.id || p.productId)"
              :class="{ 'highlight-flash-card': Number(route.query.productId) === (p.id || p.productId) }"
              :product="p"
              @add-to-cart="handleAddToCart"
            />
          </div>

          <!-- 分頁 -->
          <AppPagination
            v-model="page"
            :totalPages="totalPages"
            :totalElements="totalItems"
            :pageSize="itemsPerPage"
          />
        </main>
      </div>
    </div>

  </div>
</template>

<style scoped>
/* ============================================
   全域搜尋欄
   ============================================ */
.global-search-bar {
  display: flex;
  align-items: center;
  background: var(--bg-section);
  border: 2px solid var(--primary);
  border-radius: 50px;
  padding: 0.5rem 0.5rem 0.5rem 1.25rem;
  margin-bottom: 2.5rem;
  gap: 0.5rem;
  box-shadow: 0 4px 20px rgba(44, 76, 62, 0.12);
}

.global-search-icon {
  font-size: 1.1rem;
  flex-shrink: 0;
}

.global-search-inp {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 1rem;
  color: var(--text-primary);
  outline: none;
  font-family: inherit;
}

.global-search-btn {
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: 40px;
  padding: 0.55rem 1.5rem;
  font-size: 0.9rem;
  font-weight: 700;
  cursor: pointer;
  transition: background 0.2s;
  flex-shrink: 0;
  font-family: inherit;
}
.global-search-btn:hover { background: var(--primary-dark); }


/* ============================================
   首頁分區商品列表
   ============================================ */
.homepage-categories {
  display: flex;
  flex-direction: column;
  gap: 2.5rem;
}

.category-block {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.block-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-left: 4px solid var(--primary);
  padding-left: 0.75rem;
}

.block-title {
  font-size: 1.25rem;
  font-weight: 800;
  color: var(--text-primary);
  margin: 0;
}

.block-more-btn {
  background: transparent;
  border: 1px solid var(--primary);
  color: var(--primary);
  border-radius: 20px;
  padding: 0.35rem 1rem;
  font-size: 0.8rem;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.2s;
  font-family: inherit;
}

.block-more-btn:hover {
  background: var(--primary);
  color: #fff;
}

/* ============================================
   麵包屑
   ============================================ */
.breadcrumb {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 1.5rem;
  font-size: 0.9rem;
}

.bc-home {
  background: none;
  border: none;
  color: var(--primary);
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
  font-size: 0.9rem;
  padding: 0;
  transition: opacity 0.2s;
}
.bc-home:hover { opacity: 0.7; }
.bc-sep { color: var(--text-muted); }
.bc-current { color: var(--text-primary); font-weight: 700; }

/* ============================================
   雙欄版面
   ============================================ */
.shop-layout {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 1.5rem;
  align-items: start;
}

/* ============================================
   左側邊欄
   ============================================ */
.shop-sidebar {
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 1.25rem;
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 90px;
  max-height: calc(100vh - 120px);
  overflow-y: auto;
}

.sidebar-search { margin-bottom: 0.75rem; }

.sidebar-search-inp {
  width: 100%;
  height: 36px;
  padding: 0.4rem 0.75rem;
  border-radius: 8px;
  border: 1px solid var(--border);
  font-size: 0.85rem;
  background: var(--bg-soft);
  color: var(--text-main);
  outline: none;
  font-family: inherit;
  box-sizing: border-box;
  transition: border-color 0.2s;
}
.sidebar-search-inp:focus { border-color: var(--primary); }

.sidebar-widget {
  border-top: 1px solid var(--border);
  padding: 0.9rem 0;
}

.widget-title {
  font-size: 0.75rem;
  font-weight: 800;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  margin: 0 0 0.6rem;
}

.collapsible-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
  user-select: none;
  transition: color 0.2s;
}
.collapsible-header:hover {
  color: var(--primary);
}
.collapse-arrow {
  font-size: 0.65rem;
  color: var(--text-muted);
}

.widget-nav-list {
  display: flex;
  flex-direction: column;
  gap: 0.05rem;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  width: 100%;
  padding: 0.42rem 0.5rem;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-size: 0.83rem;
  font-family: inherit;
  cursor: pointer;
  text-align: left;
  transition: all 0.15s;
}
.nav-item:hover {
  background: rgba(44, 76, 62, 0.06);
  color: var(--primary);
}
.nav-item.active {
  background: rgba(44, 76, 62, 0.1);
  color: var(--primary);
  font-weight: 700;
}

.nav-check {
  font-size: 0.6rem;
  width: 12px;
  flex-shrink: 0;
  color: var(--primary);
}

.price-range-row {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  margin-top: 0.65rem;
}

.price-range-inp {
  flex: 1;
  height: 32px;
  padding: 0.3rem 0.4rem;
  border-radius: 6px;
  border: 1px solid var(--border);
  font-size: 0.8rem;
  background: var(--bg-soft);
  color: var(--text-main);
  outline: none;
  font-family: inherit;
  text-align: center;
  min-width: 0;
}

.price-range-sep {
  color: var(--text-muted);
  font-size: 0.82rem;
  flex-shrink: 0;
}

.sidebar-reset-btn {
  margin-top: 0.9rem;
  width: 100%;
  padding: 0.5rem;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-secondary);
  font-size: 0.8rem;
  font-family: inherit;
  cursor: pointer;
  font-weight: 600;
  transition: all 0.2s;
}
.sidebar-reset-btn:hover {
  border-color: var(--primary);
  color: var(--primary);
  background: rgba(44, 76, 62, 0.04);
}

/* ============================================
   右側商品區
   ============================================ */
.shop-main { flex: 1; }

.result-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1.5rem;
  flex-wrap: wrap;
  border-bottom: 1px solid var(--border);
  padding-bottom: 0.75rem;
}

.result-left {
  display: flex;
  align-items: center;
  gap: 1.5rem;
  flex-wrap: nowrap;
  white-space: nowrap;
}

.result-count {
  font-size: 0.85rem;
  color: var(--text-secondary);
  font-weight: 600;
  white-space: nowrap;
}


.active-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.active-tag {
  display: flex;
  align-items: center;
  gap: 0.3rem;
  background: rgba(44, 76, 62, 0.08);
  color: var(--primary);
  font-size: 0.78rem;
  font-weight: 600;
  padding: 0.22rem 0.6rem;
  border-radius: 20px;
}

.tag-remove {
  background: none;
  border: none;
  color: var(--primary);
  cursor: pointer;
  padding: 0;
  font-size: 0.72rem;
  line-height: 1;
  opacity: 0.7;
  font-family: inherit;
}
.tag-remove:hover { opacity: 1; }

.empty-state {
  text-align: center;
  padding: 5rem 0;
  color: var(--text-secondary);
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 260px));
  gap: 1.5rem;
  justify-content: start;
}

/* Homepage category sections: auto wrapping with fixed 260px width, aligned left */
.home-grid {
  grid-template-columns: repeat(auto-fill, minmax(260px, 260px));
  gap: 1.5rem;
  justify-content: start;
}


/* ============================================
   RWD
   ============================================ */
@media (max-width: 900px) {
  .shop-layout {
    grid-template-columns: 1fr;
  }
  .shop-sidebar {
    position: relative;
    top: 0;
  }
}
</style>

