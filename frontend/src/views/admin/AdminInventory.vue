<template>
  <div class="page-container admin-dashboard">
    <span class="section-tag">ADMIN</span>
    <h1 class="section-title">安全庫存監控</h1>
    <p class="section-desc">監控與管理補給商城商品的庫存水位，並隨時調整安全警示門檻。</p>

    <!-- 庫存統計與狀態及自訂門檻工具 -->
    <div class="inventory-header card" style="display: flex; justify-content: space-between; align-items: center; gap: 20px; flex-wrap: wrap; margin-bottom: 1.5rem; padding: 1.25rem; background: #ffffff; border: 1px solid #e2e8f0; border-radius: 16px; box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.02);">
      <div class="status-indicator" :class="lowStockProducts.length > 0 ? 'warning' : 'success'" style="flex: 1; min-width: 250px; margin: 0; padding: 0; border: none; background: transparent; font-weight: 600; font-size: 0.92rem;">
        <span v-if="safetyThreshold === null" style="color: #64748b;">載入中，請稍候...</span>
        <template v-else>
          <span v-if="lowStockProducts.length > 0">
            警示：目前共有 <strong>{{ lowStockProducts.length }}</strong> 項商品處於低庫存狀態 (少於最低安全庫存 {{ safetyThreshold }} 件)。請即時進行補貨調整。
          </span>
          <span v-else>
            目前無商品處於低庫存狀態。所有商品庫存水位皆在安全標準（{{ safetyThreshold }} 件）以上。
          </span>
        </template>
      </div>
      
      <!-- 門檻調整工具 -->
      <div class="threshold-tweak-box" style="display: flex; align-items: center; gap: 10px; background: #f8fafc; padding: 0.5rem 1rem; border-radius: 10px; border: 1px solid #e2e8f0;">
        <label style="color: #475569; font-size: 0.85rem; white-space: nowrap; font-weight: 600;">自訂安全庫存門檻:</label>
        <input type="number" v-model.number="tempThreshold" min="1" max="100" style="width: 70px; text-align: center; background: #ffffff; border: 1px solid #cbd5e1; color: #0f172a; padding: 0.3rem 0.5rem; border-radius: 6px; font-weight: bold; outline: none;" />
        <button class="btn btn-primary btn-xs" @click="saveThreshold">儲存門檻</button>
      </div>
    </div>

    <!-- 篩選標籤 -->
    <div class="inventory-tabs">
      <button 
        class="tab-btn" 
        :class="{ active: currentTab === 'low' }" 
        @click="currentTab = 'low'"
      >
        低於安全庫存 ({{ lowStockProducts.length }})
      </button>
      <button 
        class="tab-btn" 
        :class="{ active: currentTab === 'all' }" 
        @click="currentTab = 'all'"
      >
        全部在架商品 ({{ activeProducts.length }})
      </button>
    </div>

    <!-- 商品列表 -->
    <div class="admin-table card">
      <div class="product-table-wrap">
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>商品圖片</th>
              <th>品項名稱 & 說明</th>
              <th>類型</th>
              <th>定價金額</th>
              <th>當前庫存</th>
              <th>最低安全庫存</th>
              <th>庫存狀態</th>
              <th style="text-align: center;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in filteredProducts" :key="p.productId">
              <td>#{{ p.productId }}</td>
              <td>
                <img :src="getProductImage(p)" class="product-thumb" alt="Product Image" />
              </td>
              <td class="product-name-cell">
                <div class="name-text">{{ p.name }}</div>
                <div class="desc-text" :title="p.description">{{ p.description || '無詳細說明' }}</div>
                <div v-if="p.type === 'COURSE'" class="trainer-name-label" style="font-size: 0.72rem; color: var(--primary); font-weight: 700; margin-top: 0.2rem;">
                  指導教練: {{ p.trainerName || '多位教練/未分配' }}
                </div>
              </td>
              <td>
                <span :class="['badge', p.type === 'COURSE' ? 'badge-course' : p.type === 'SUBSCRIPTION' ? 'badge-subscription' : 'badge-goods']">
                  {{ p.type === 'COURSE' ? '課程' : p.type === 'SUBSCRIPTION' ? '訂閱' : '商品' }}
                </span>
              </td>
              <td class="price-text">NT$ {{ Number(p.price).toLocaleString() }}</td>
              <td class="stock-text" :class="{ 'low-stock': p.stock <= safetyThreshold }">
                {{ p.stock }}
              </td>
              <td>
                <span class="safety-threshold-label">{{ safetyThreshold }} 件</span>
              </td>
              <td>
                <div class="status-cell-wrap">
                  <span :class="['status-dot', p.stock <= 0 ? 'critical' : p.stock <= safetyThreshold ? 'warning' : 'success']"></span>
                  <span :class="['status-text-label', p.stock <= 0 ? 'text-critical' : p.stock <= safetyThreshold ? 'text-warning' : 'text-success']">
                    {{ p.stock <= 0 ? '已售罄' : p.stock <= safetyThreshold ? '庫存緊張' : '充足' }}
                  </span>
                </div>
              </td>
              <td style="text-align: center;">
                <RouterLink to="/admin/products" class="btn btn-primary btn-xs" style="text-decoration: none;">
                  前往補貨
                </RouterLink>
              </td>
            </tr>
            <tr v-if="filteredProducts.length === 0">
              <td colspan="9" style="text-align: center; padding: 3rem; color: #94a3b8;">
                無符合篩選條件的商品資料
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import api from '@/api/axios.instance.js'

// [響應式狀態] products (products)
// 為什麼要這麼做：在架商品清單，提供頁面渲染商品列表的資料來源。
const products = ref([])
// [響應式狀態] currentTab (currenttab)
// 為什麼要這麼做：目前選取的資料，以便進行單一資料展示、編輯或預約操作。
const currentTab = ref('low')
// [響應式狀態] safetyThreshold (safetythreshold)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const safetyThreshold = ref(null)  // null = 尚未從後端載入
// [響應式狀態] tempThreshold (tempthreshold)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const tempThreshold = ref(20)

// [功能方法] loadProducts (載入products)
// 為什麼要這麼做：向後端 API 請求最新數據，確保頁面載入或重新整理時，使用者能看到最即時且正確的資料。
async function loadProducts() {
  try {
    const res = await api.get('/api/products', { params: { size: 100 } })
    products.value = res.data.content || []
  } catch (e) {
    console.error('Failed to load products for inventory check', e)
  }
}

async function loadThreshold() {
  const localVal = localStorage.getItem('safety_stock_threshold')
  const defaultVal = localVal ? Number(localVal) : 5
  safetyThreshold.value = defaultVal
  tempThreshold.value = defaultVal
}

// [功能方法] saveThreshold (儲存threshold)
// 為什麼要這麼做：將當前編輯完成的配置或資料儲存至本地，以確保變更不會因頁面重整而遺失。
async function saveThreshold() {
  if (!tempThreshold.value || tempThreshold.value < 1) {
    alert('請輸入有效的門檻數量 (大於 0)')
    return
  }
  localStorage.setItem('safety_stock_threshold', tempThreshold.value.toString())
  safetyThreshold.value = tempThreshold.value
  alert(`安全庫存門檻已成功更新為 ${tempThreshold.value} 件！`)
}

// [計算屬性] activeProducts (activeproducts)
// 為什麼要這麼做：在架商品清單，提供頁面渲染商品列表的資料來源。
const activeProducts = computed(() => {
  return products.value.filter(p => p.status === 'ACTIVE')
})

// [計算屬性] lowStockProducts (lowstockproducts)
// 為什麼要這麼做：在架商品清單，提供頁面渲染商品列表的資料來源。
const lowStockProducts = computed(() => {
  if (safetyThreshold.value === null) return []
  return activeProducts.value.filter(p => p.stock <= safetyThreshold.value)
})

// [計算屬性] filteredProducts (filteredproducts)
// 為什麼要這麼做：依據指定屬性將不符合條件的陣列元素濾除，確保畫面只呈現使用者感興趣的資料。
const filteredProducts = computed(() => {
  if (currentTab.value === 'low') {
    return lowStockProducts.value
  }
  return activeProducts.value
})

// [功能方法] formatImageUrl (格式化imageurl)
// 為什麼要這麼做：將原始的資料（如日期、數值）轉換為符合本地語系與高可讀性的格式（如千分位、kg 單位）。
function formatImageUrl(url) {
  if (!url || typeof url !== 'string') return ''
  const apiUrl = import.meta.env.VITE_API_URL
  if (apiUrl && url.includes('localhost:8080')) {
    const base = apiUrl.endsWith('/') ? apiUrl.slice(0, -1) : apiUrl
    return url.replace('http://localhost:8080', base)
  }
  if (url.startsWith('/uploads/') && apiUrl) {
    const base = apiUrl.endsWith('/') ? apiUrl.slice(0, -1) : apiUrl
    return base + url
  }
  return url
}

// [功能方法] getProductImage (取得productimage)
// 為什麼要這麼做：向伺服器查詢並獲取指定資料，以供畫面渲染或後續邏輯判斷使用。
function getProductImage(product) {
  if (product.imageUrl) return formatImageUrl(product.imageUrl)
  return 'https://images.unsplash.com/photo-1517838277536-f5f99be501cd?w=200&auto=format&fit=crop&q=60'
}

// [生命週期掛載] 組件初始化載入
// 為什麼要這麼做：在 Vue 元件完全掛載至網頁 DOM 後，立即向後端發送請求載入初始頁面資料，提供即時的視覺展現。
onMounted(() => {
  loadProducts()
  loadThreshold()
})
</script>

<style scoped>
.inventory-tabs {
  display: flex;
  gap: 0.5rem;
  margin: 1.5rem 0 1rem 0;
  border-bottom: 1px solid #e2e8f0;
  padding-bottom: 0.5rem;
}

.tab-btn {
  background: transparent;
  border: none;
  color: #64748b;
  padding: 0.5rem 1rem;
  font-size: 0.9rem;
  cursor: pointer;
  border-radius: 8px;
  transition: all 0.2s ease;
}

.tab-btn:hover {
  color: #0f172a;
  background: #f1f5f9;
}

.tab-btn.active {
  color: #ffffff;
  background: #3c302b;
  font-weight: 600;
}

.safety-threshold-label {
  color: #94a3b8;
  font-size: 0.85rem;
}

.status-dot.critical {
  background-color: #ef4444;
  box-shadow: 0 0 8px rgba(239, 68, 68, 0.5);
}

.status-dot.warning {
  background-color: #f59e0b;
  box-shadow: 0 0 8px rgba(245, 158, 11, 0.5);
}

.status-dot.success {
  background-color: #10b981;
  box-shadow: 0 0 8px rgba(16, 185, 129, 0.5);
}

.text-critical {
  color: #ef4444;
}

.text-warning {
  color: #f59e0b;
}

.text-success {
  color: #10b981;
}

.btn-xs {
  font-size: 0.72rem;
  padding: 0.35rem 0.6rem;
  border-radius: 6px;
}

/* ===== 後台通用資料表格樣式===== */
.admin-table {
  border-radius: 16px;
  padding: 0;
  overflow: hidden;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.02);
  border: 1px solid #e2e8f0;
  background: #ffffff;
  margin-top: 1rem;
}
.admin-table table {
  width: 100%;
  border-collapse: collapse;
}
.admin-table table th {
  background: #f8fafc;
  color: #475569;
  font-weight: 700;
  padding: 0.75rem 1rem;
  text-align: center;
  border-bottom: 1px solid #e2e8f0;
  font-size: 0.85rem;
  letter-spacing: 0.3px;
  vertical-align: middle;
}
.admin-table table td {
  padding: 0.75rem 1rem;
  border-bottom: 1px solid #f1f5f9;
  color: #334155; /* 深灰色字體，避免白色 */
  font-size: 0.85rem;
  vertical-align: middle;
  text-align: center;
}

/* 九個欄位擠在固定寬度裡，品項名稱與說明本來就需要折行才看得完，
   所以不對整列下 nowrap——只讓「本來就該一行講完」的數字欄不折。
   「前往補貨」按鈕的不折行由 main.css 的 .btn 統一處理，這裡不必重複。
   （先前為了修按鈕折行對整個 td 下 nowrap，結果把名稱欄的文字截斷了） */
.admin-table table td.price-text,
.admin-table table td.stock-text {
  white-space: nowrap;
}

/* 儲存格不折行後，視窗窄時改由表格自己橫向捲動，不讓整頁跟著左右跑。
   .admin-table 的 overflow:hidden 是給圓角用的，捲動要放在內層這一層 */
.product-table-wrap {
  overflow-x: auto;
}
.admin-table table tr:hover td {
  background: #f8fafc;
}
.product-thumb {
  width: 50px;
  height: 50px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}
.product-name-cell {
  text-align: left !important;
}
.product-name-cell .name-text {
  font-weight: 700;
  color: #0f172a; /* 黑色字體，避免白色 */
  margin-bottom: 0.25rem;
  /* 商品名稱可能很長（尤其含規格描述），欄位又只有一百多 px，
     跟底下的說明一樣讓它折行，不要截斷成看不出是哪個商品 */
  white-space: normal;
  word-break: break-word;
}
.product-name-cell .desc-text {
  font-size: 0.75rem;
  color: #64748b;
  white-space: normal;
  word-break: break-word;
  line-height: 1.4;
}
.badge {
  display: inline-flex;
  align-items: center;
  padding: 0.25rem 0.6rem;
  font-size: 0.75rem;
  font-weight: 600;
  border-radius: 6px;
}
.badge-goods {
  background: #e0f2fe;
  color: #0369a1;
}
.badge-course {
  background: #fef3c7;
  color: #b45309;
}
.badge-subscription {
  background: #dcfce7;
  color: #15803d;
}
.price-text {
  font-weight: 600;
  color: #0f172a;
}
.stock-text {
  font-weight: 700;
  color: #0f172a;
}
.stock-text.low-stock {
  color: #ef4444;
}
</style>
