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
        <AppButton variant="primary" class="btn-xs" @click="saveThreshold">儲存門檻</AppButton>
      </div>
    </div>

    <!-- 篩選標籤 -->
    <div class="erp-tabs" style="display: flex; gap: 1.5rem; border-bottom: 1.5px solid var(--border, #e2e8f0); margin-bottom: 1.5rem;">
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
    <AdminTable
      v-model:currentPage="currentPage"
      v-model:itemsPerPage="itemsPerPage"
      :data="paginatedProducts"
      :columns="tableColumns"
      :totalItems="totalItems"
      rowKey="productId"
    >
      <template #productId="{ value }">
        #{{ value }}
      </template>

      <template #imageUrl="{ row }">
        <div style="width: 70px; height: 70px; overflow: hidden; border-radius: 8px; border: 1px solid #e2e8f0; display: flex; align-items: center; justify-content: center; margin: 0 auto; background: #fafafa;">
          <img :src="getProductImage(row)" class="product-thumb" alt="Product Image"
               @error="useFallbackImage($event, DEFAULT_PRODUCT_IMAGE)" style="width: 100%; height: 100%; object-fit: contain;" />
        </div>
      </template>

      <template #name="{ row }">
        <div class="product-name-cell">
          <div class="name-text">{{ row.name }}</div>
          <div class="desc-text" :title="row.description">{{ row.description || '無詳細說明' }}</div>
          <div v-if="row.type === 'COURSE'" class="trainer-name-label" style="font-size: 0.72rem; color: var(--primary); font-weight: 700; margin-top: 0.2rem;">
            指導教練: {{ row.trainerName || '多位教練/未分配' }}
          </div>
        </div>
      </template>

      <template #price="{ value }">
        <span class="price-text">NT$ {{ Number(value).toLocaleString() }}</span>
      </template>

      <template #stock="{ value }">
        <span class="stock-text" :class="{ 'low-stock': value <= safetyThreshold }">
          {{ value }}
        </span>
      </template>

      <template #safetyThreshold>
        <span class="safety-threshold-label">{{ safetyThreshold }} 件</span>
      </template>

      <template #status="{ row }">
        <div class="status-cell-wrap">
          <span :class="['status-dot', row.stock <= 0 ? 'critical' : row.stock <= safetyThreshold ? 'warning' : 'success']"></span>
          <span :class="['status-text-label', row.stock <= 0 ? 'text-critical' : row.stock <= safetyThreshold ? 'text-warning' : 'text-success']">
            {{ row.stock <= 0 ? '已售罄' : row.stock <= safetyThreshold ? '庫存緊張' : '充足' }}
          </span>
        </div>
      </template>

      <template #actions>
        <RouterLink to="/admin/products" class="btn btn-primary btn-xs" style="text-decoration: none;">
          前往補貨
        </RouterLink>
      </template>
    </AdminTable>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import api from '@/api/axios.instance.js'
import { formatImageUrl, useFallbackImage, DEFAULT_PRODUCT_IMAGE } from '@/utils/image.js'
import AdminTable from '@/components/AdminTable.vue'
import AppButton from '@/components/AppButton.vue'

// [響應式狀態] products
const products = ref([])
// [響應式狀態] currentTab
const currentTab = ref('low')
// [響應式狀態] safetyThreshold
const safetyThreshold = ref(null)
// [響應式狀態] tempThreshold
const tempThreshold = ref(20)

// 分頁狀態
const currentPage = ref(1)
const itemsPerPage = ref(10)

const tableColumns = [
  { key: 'productId', label: 'ID', width: '60px' },
  { key: 'imageUrl', label: '商品圖片', width: '110px' },
  { key: 'name', label: '品項名稱 & 說明', width: '220px' },
  { key: 'price', label: '定價金額', width: '100px' },
  { key: 'stock', label: '當前庫存', width: '85px' },
  { key: 'safetyThreshold', label: '最低安全庫存', width: '100px' },
  { key: 'status', label: '庫存狀態', width: '100px' },
  { key: 'actions', label: '操作', width: '120px' }
]

// 當篩選標籤或每頁筆數改變時，重置頁碼
watch([currentTab, itemsPerPage], () => {
  currentPage.value = 1
})

// [功能方法] loadProducts
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

// [功能方法] saveThreshold
async function saveThreshold() {
  if (!tempThreshold.value || tempThreshold.value < 1) {
    alert('請輸入有效的門檻數量 (大於 0)')
    return
  }
  localStorage.setItem('safety_stock_threshold', tempThreshold.value.toString())
  safetyThreshold.value = tempThreshold.value
  alert(`安全庫存門檻已成功更新為 ${tempThreshold.value} 件！`)
}

// [計算屬性] activeProducts
const activeProducts = computed(() => {
  return products.value.filter(p => p.status === 'ACTIVE')
})

// [計算屬性] lowStockProducts
const lowStockProducts = computed(() => {
  if (safetyThreshold.value === null) return []
  return activeProducts.value.filter(p => p.stock <= safetyThreshold.value)
})

// [計算屬性] filteredProducts
const filteredProducts = computed(() => {
  if (currentTab.value === 'low') {
    return lowStockProducts.value
  }
  return activeProducts.value
})

const totalItems = computed(() => filteredProducts.value.length)

const paginatedProducts = computed(() => {
  const start = (currentPage.value - 1) * itemsPerPage.value
  const end = start + itemsPerPage.value
  return filteredProducts.value.slice(start, end)
})

// [功能方法] getProductImage
function getProductImage(product) {
  if (product.imageUrl) return formatImageUrl(product.imageUrl)
  return DEFAULT_PRODUCT_IMAGE
}

// [生命週期掛載]
onMounted(() => {
  loadProducts()
  loadThreshold()
})
</script>

<style scoped>
.erp-tabs .tab-btn {
  background: none;
  border: none;
  padding: 0.75rem 1.5rem;
  font-size: 0.95rem;
  font-weight: 700;
  color: var(--text-secondary, #64748b);
  cursor: pointer;
  border-bottom: 3px solid transparent;
  transition: all 0.25s ease;
}
.erp-tabs .tab-btn:hover {
  color: var(--primary, #5a4a42);
}
.erp-tabs .tab-btn.active {
  color: var(--primary, #5a4a42);
  border-bottom-color: var(--primary, #5a4a42);
}

.safety-threshold-label {
  color: #94a3b8;
  font-size: 0.85rem;
}

/* ===== 狀態標籤與徽章樣式 ===== */
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

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
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

.status-cell-wrap {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
}

.status-text-label {
  font-weight: 600;
  font-size: 0.8rem;
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

.product-thumb {
  width: 70px;
  height: 70px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  margin: 0 auto;
}
.product-name-cell {
  text-align: center;
}
.product-name-cell .name-text {
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 0.25rem;
}
.product-name-cell .desc-text {
  font-size: 0.72rem;
  color: #64748b;
  white-space: normal !important;
  word-break: break-word !important;
  overflow: visible !important;
  text-align: center;
  line-height: 1.5;
  margin-top: 0.2rem;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
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
