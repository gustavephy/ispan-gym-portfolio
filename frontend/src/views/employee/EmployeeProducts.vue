<template>
  <div class="page-container admin-dashboard">
    <div class="page-header-row" style="display: flex; gap: 1rem;">
      <button class="btn btn-primary" @click="showAddProduct = !showAddProduct">
        <span>{{ showAddProduct ? '✕ 取消新增' : '＋ 新增商品' }}</span>
      </button>
      <button class="btn btn-outline" @click="showCategoryManager = true">
        <span>⚙ 管理商品分類</span>
      </button>
    </div>

    <!-- 新增商品表單 -->
    <Transition name="slide">
      <div v-if="showAddProduct" class="card add-form-card">
        <div class="form-header">
          <h4>新增商品資料</h4>
          <span class="form-helper">*所有欄位均為必填</span>
        </div>
        <form @submit.prevent="createProduct">
          <div class="form-grid-responsive">
            <div class="form-group">
              <label class="form-label">商品名稱</label>
              <input v-model="productForm.name" class="form-input" placeholder="例如: 智慧降噪水壺" required />
            </div>
            <div class="form-group">
              <label class="form-label">所屬分類</label>
              <select v-model="productForm.productCategoryId" class="form-input" required>
                <option :value="null" disabled selected>請選擇商品分類</option>
                <option v-for="cat in goodsCategories" :key="cat.id" :value="cat.id">{{ cat.name }}</option>
              </select>
            </div>
            <div class="form-group">
              <label class="form-label">定價售價 (NT$)</label>
              <input v-model.number="productForm.price" type="number" class="form-input" min="1" placeholder="請輸入金額" required />
            </div>
            <div class="form-group">
              <label class="form-label">目前庫存數量</label>
              <input v-model.number="productForm.stock" type="number" class="form-input" min="0" placeholder="庫存數量" required />
            </div>
          </div>
          <div class="form-group file-upload-group">
            <label class="form-label">商品照片 <span style="color:#94a3b8;font-size:0.8em">（限 5MB 以內）</span></label>
            <div class="file-upload-wrapper">
              <label class="custom-file-btn">
                選擇照片
                <input type="file" @change="e => uploadFile(e, 'add')" accept="image/*" class="hidden-file-input" />
              </label>
              <span v-if="uploadingFile" class="loading-spinner-inline"></span>
              <span class="file-status-text" v-if="productForm.imageUrl">已上傳圖片</span>
              <img v-if="productForm.imageUrl" :src="formatImageUrl(productForm.imageUrl)" class="form-preview-thumb" alt="Preview" />
            </div>
            <input v-model="productForm.imageUrl" class="form-input url-input" placeholder="或直接貼上外部圖片的 URL 網址..." />
          </div>
          <div class="form-group" style="margin-top: 1rem;">
            <label class="form-label">詳細說明介紹</label>
            <textarea v-model="productForm.description" class="form-input" rows="3" placeholder="請填寫此項目的特色、規格或內容介紹..."></textarea>
          </div>
          <div class="form-actions">
            <button type="submit" class="btn btn-primary">確認發佈上架</button>
          </div>
        </form>
      </div>
    </Transition>

    <!-- 搜尋與篩選條件區 -->
    <SearchFilter 
      v-model="filterParams"
      keyword-label="商品搜尋"
      keyword-placeholder="搜尋名稱、說明..."
      date-label="上架日期範圍"
      @reset="resetFilters"
    />

    <!-- 快速分類篩選列 -->
    <CategoryFilter 
      v-model="activeTableFilter"
      :options="tableFilters"
    />

    <!-- 商品列表表格 -->
    <div class="admin-table card">
      <!-- 控制列：商品顯示數量與分頁頁碼 (Top) -->
      <div class="list-control-header bg-none" style="margin: 1rem 1rem 1.5rem 1rem; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 1rem;">
        <div style="display: flex; align-items: center; gap: 1.5rem; flex-wrap: wrap;">
          <span class="total-count-text">共 {{ totalItems }} 筆商品</span>
          <div class="page-size-selector">
            <label for="itemsPerPageSelect">顯示數量：</label>
            <select id="itemsPerPageSelect" v-model.number="itemsPerPage" class="select-sm">
              <option :value="5">5 筆</option>
              <option :value="10">10 筆</option>
              <option :value="20">20 筆</option>
              <option :value="50">50 筆</option>
            </select>
          </div>
        </div>

        <!-- 分頁頁碼 (Top) -->
        <div class="pagination" v-if="totalPages > 1" style="margin: 0; display: flex; gap: 0.5rem;">
          <button type="button" class="page-btn" :disabled="currentPage === 1" @click="currentPage--">‹</button>
          <template v-for="n in visiblePages" :key="n">
            <span v-if="n === '...'" class="page-ellipsis" style="display: flex; align-items: center; justify-content: center; width: 28px; height: 28px; color: var(--text-secondary); font-size: 0.8rem;">...</span>
            <button
              v-else
              type="button"
              :class="['page-btn', { active: currentPage === n }]"
              @click="currentPage = n"
            >
              {{ n }}
            </button>
          </template>
          <button type="button" class="page-btn" :disabled="currentPage === totalPages" @click="currentPage++">›</button>
        </div>
      </div>

      <div class="product-table-wrap">
      <table>
        <thead>
          <tr>
            <th class="hide-md">ID</th>
            <th>商品圖片</th>
            <th>商品名稱 & 說明</th>
            <th class="hide-sm">所屬分類</th>
            <th>定價金額</th>
            <th>目前庫存</th>
            <th class="hide-md">狀態</th>
            <th>功能操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in paginatedProducts" :key="p.id">
            <td class="hide-md">#{{ p.id }}</td>
            <td>
              <img :src="getProductImage(p)" class="product-thumb" alt="Product Image" />
            </td>
            <td class="product-name-cell">
              <div class="name-text">{{ p.name }}</div>
              <div class="desc-text hide-sm" :title="p.description">{{ p.description || '無詳細說明' }}</div>
            </td>
            <td class="hide-sm">
              <span class="badge badge-success">
                {{ p.categoryName || '無分類' }}
              </span>
            </td>
            <td class="price-text">NT$ {{ Number(p.price).toLocaleString() }}</td>
            <td :class="['stock-text', { 'low-stock': p.stock <= 5 }]">
              {{ p.stock }}
              <span v-if="p.stock <= 5" class="low-stock-label">緊張</span>
            </td>
            <td class="hide-md">
              <div class="status-cell-wrap">
                <span :class="['status-dot', p.status === 1 || p.status === 'ACTIVE' ? 'active' : 'inactive']"></span>
                <span :class="['status-text-label', p.status === 1 || p.status === 'ACTIVE' ? 'text-active' : 'text-inactive']">
                  {{ p.status === 1 || p.status === 'ACTIVE' ? '上架中' : '已下架' }}
                </span>
              </div>
            </td>
            <td>
              <div class="action-buttons-wrap">
                <button class="btn btn-edit edit-btn-style" @click="openEditModal(p)">編輯</button>
                <button v-if="p.status === 1 || p.status === 'ACTIVE'" class="btn btn-danger deactive-btn-style" @click="deleteProduct(p.productId)">下架</button>
              </div>
            </td>
          </tr>
          <tr v-if="filteredTableProducts.length === 0">
            <td colspan="8" style="text-align: center; padding: 3rem; color: #94a3b8;">
              沒有符合當前篩選條件的商品項目
            </td>
          </tr>
        </tbody>
      </table>
      </div><!-- /.product-table-wrap -->

      <!-- 分頁頁碼 (Bottom) -->
      <div class="pagination" v-if="totalPages > 1" style="margin: 1rem 0; justify-content: center; display: flex; gap: 0.5rem;">
        <button type="button" class="page-btn" :disabled="currentPage === 1" @click="currentPage--">‹</button>
        <template v-for="n in visiblePages" :key="n">
          <span v-if="n === '...'" class="page-ellipsis" style="display: flex; align-items: center; justify-content: center; width: 28px; height: 28px; color: var(--text-secondary); font-size: 0.8rem;">...</span>
          <button
            v-else
            type="button"
            :class="['page-btn', { active: currentPage === n }]"
            @click="currentPage = n"
          >
            {{ n }}
          </button>
        </template>
        <button type="button" class="page-btn" :disabled="currentPage === totalPages" @click="currentPage++">›</button>
      </div>
    </div>

    <!-- 編輯商品 Modal -->
    <AppModal v-model:visible="showEditProduct" title="編輯商品內容" icon="" size="md">
      <form id="editProductForm" @submit.prevent="updateProduct">
        <div class="form-group">
          <label class="form-label">商品名稱</label>
          <input v-model="editForm.name" class="form-input" required />
        </div>
        <div class="form-grid-responsive">
          <div class="form-group">
            <label class="form-label">商品分類</label>
            <select v-model="editForm.productCategoryId" class="form-input">
              <option :value="null">無分類</option>
              <option v-for="cat in goodsCategories" :key="cat.id" :value="cat.id">{{ cat.name }}</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">售價 (NT$)</label>
            <input v-model.number="editForm.price" type="number" class="form-input" min="1" required />
          </div>
          <div class="form-group">
            <label class="form-label">庫存數量</label>
            <input v-model.number="editForm.stock" type="number" class="form-input" min="0" required />
          </div>
        </div>
        <div class="form-group" style="margin-top: 1rem;">
          <label class="form-label">照片設定 <span style="color:#94a3b8;font-size:0.8em">（限 5MB 以內）</span></label>
          <div class="file-upload-wrapper">
            <label class="custom-file-btn">
              重新上傳
              <input type="file" @change="e => uploadFile(e, 'edit')" accept="image/*" class="hidden-file-input" />
            </label>
            <span v-if="uploadingFile" class="loading-spinner-inline"></span>
            <img v-if="editForm.imageUrl" :src="formatImageUrl(editForm.imageUrl)" class="form-preview-thumb" alt="Preview" />
          </div>
          <input v-model="editForm.imageUrl" class="form-input url-input" placeholder="圖片位址 URL" />
        </div>
        <div class="form-group" style="margin-top: 1rem;">
          <label class="form-label">詳細說明介紹</label>
          <textarea v-model="editForm.description" class="form-input" rows="3"></textarea>
        </div>
      </form>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="showEditProduct = false">取消</button>
        <button type="submit" form="editProductForm" class="btn btn-primary">保存修改資料</button>
      </template>
    </AppModal>

    <!-- 分類管理 Modal -->
    <AppModal v-model:visible="showCategoryManager" title="管理商品分類" icon="" size="md">
      <div class="category-manager-content">
        <!-- 新增分類表單 -->
        <form @submit.prevent="createCategory" class="card" style="padding: 1rem; margin-bottom: 1.5rem; background: var(--bg-soft);">
          <h5 style="margin-bottom: 0.75rem; font-weight: 700;">新增分類</h5>
          <div style="display: flex; gap: 0.5rem; flex-wrap: wrap;">
            <input v-model="newCategoryForm.name" class="form-input" style="flex: 2; min-width: 150px;" placeholder="分類名稱，例如：健身器材" required />
            <button type="submit" class="btn btn-primary" style="padding: 0.5rem 1rem;">新增</button>
          </div>
        </form>

        <!-- 分類列表 -->
        <div class="card" style="padding: 1rem; max-height: 300px; overflow-y: auto;">
          <h5 style="margin-bottom: 0.75rem; font-weight: 700;">目前分類清單</h5>
          <table style="width: 100%; border-collapse: collapse;">
            <thead>
              <tr style="border-bottom: 1px solid var(--border); text-align: left;">
                <th style="padding: 0.5rem;">名稱</th>
                <th style="padding: 0.5rem; text-align: right;">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="cat in goodsCategories" :key="cat.productCategoryId" style="border-bottom: 1px solid var(--border-light);">
                <td style="padding: 0.5rem; font-weight: 600;">{{ cat.name }}</td>
                <td style="padding: 0.5rem; text-align: right;">
                  <button @click="deleteCategory(cat)" class="btn btn-danger btn-sm" style="padding: 0.25rem 0.5rem; font-size: 0.75rem;">刪除</button>
                </td>
              </tr>
              <tr v-if="goodsCategories.length === 0">
                <td colspan="2" style="text-align: center; padding: 1rem; color: var(--text-muted);">無分類資料</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <template #footer>
        <button type="button" class="btn btn-primary" @click="showCategoryManager = false">完成</button>
      </template>
    </AppModal>

    <div class="dashboard-footer-blank"></div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, inject, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useEmployeeStore } from '@/stores/employee.js'
import AppModal from '@/components/AppModal.vue'
import SearchFilter from '@/components/SearchFilter.vue'
import CategoryFilter from '@/components/CategoryFilter.vue'
import { formatImageUrl } from '@/utils/image.js'

const employeeStore = useEmployeeStore()
const showToast = inject('showToast')

// [響應式狀態] showAddProduct (顯示新增product)
// 為什麼要這麼做：開啟或切換特定版面、提示或按鈕的顯示狀態，實現動態的 UI 響應式交互。
const showAddProduct = ref(false)
// [響應式狀態] showEditProduct (顯示編輯product)
// 為什麼要這麼做：開啟或切換特定版面、提示或按鈕的顯示狀態，實現動態的 UI 響應式交互。
const showEditProduct = ref(false)
// [響應式狀態] showCategoryManager (顯示categorymanager)
// 為什麼要這麼做：開啟或切換特定版面、提示或按鈕的顯示狀態，實現動態的 UI 響應式交互。
const showCategoryManager = ref(false)
// [響應式狀態] uploadingFile (uploadingfile)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const uploadingFile = ref(false)
// [響應式狀態] activeTableFilter (activetable篩選)
// 為什麼要這麼做：依據指定屬性將不符合條件的陣列元素濾除，確保畫面只呈現使用者感興趣的資料。
const activeTableFilter = ref('ALL')

// 搜尋與篩選狀態
// [響應式狀態] filterParams (篩選params)
// 為什麼要這麼做：依據指定屬性將不符合條件的陣列元素濾除，確保畫面只呈現使用者感興趣的資料。
const filterParams = ref({
  keyword: '',
  startDate: '',
  endDate: ''
})

// [功能方法] resetFilters (重置filters)
// 為什麼要這麼做：將所有篩選條件、分頁或表單狀態還原至預設初值，解決狀態殘留引起的查詢問題。
function resetFilters() {
  filterParams.value.keyword = ''
  filterParams.value.startDate = ''
  filterParams.value.endDate = ''
}

// 當篩選條件改變時，重置頁碼到第一頁
// [監聽器] 監聽特定狀態變化
// 為什麼要這麼做：當監聽的數據或屬性發生異動時，即時觸發與其關聯的連動業務邏輯、重新計算數值或重新載入資料。
watch([activeTableFilter, () => filterParams.value.keyword, () => filterParams.value.startDate, () => filterParams.value.endDate], () => {
  currentPage.value = 1
})

// [自 Store 解構響應式屬性] products, productCategories
// - products 為什麼要這麼做：在架商品清單，提供頁面渲染商品列表的資料來源。
// - productCategories 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const { products, productCategories } = storeToRefs(employeeStore)

// [響應式狀態] productForm (productform)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const productForm = ref({ name: '', price: null, stock: null, description: '', imageUrl: '', productCategoryId: null })
// [響應式狀態] editForm (編輯form)
// 為什麼要這麼做：開啟編輯模式或彈窗，並預先載入欲修改項目的現有資料，提供良好的使用者修改體驗。
const editForm = ref({ id: null, name: '', price: null, stock: null, description: '', imageUrl: '', productCategoryId: null })
// [響應式狀態] newCategoryForm (newcategoryform)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const newCategoryForm = ref({ name: '', categoryType: 'PHYSICAL', iconUrl: '', sortOrder: 0 })

// [計算屬性] goodsCategories (goodscategories)
// 為什麼要這麼做：執行頁面跳轉導航，帶領使用者前往指定的路徑，並可選擇攜帶特定的 query 查詢參數。
const goodsCategories = computed(() => {
  return productCategories.value.filter(c => c.name !== '月卡訂閱')
})

// [計算屬性] tableFilters (tablefilters)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const tableFilters = computed(() => {
  const list = [
    { value: 'ALL', label: '全部實體商品' }
  ]
  goodsCategories.value.forEach(cat => {
    list.push({ value: String(cat.productCategoryId), label: cat.name })
  })
  list.push({ value: 'INACTIVE', label: '已下架商品' })
  return list
})

// [計算屬性] filteredTableProducts (filteredtableproducts)
// 為什麼要這麼做：依據指定屬性將不符合條件的陣列元素濾除，確保畫面只呈現使用者感興趣的資料。
const filteredTableProducts = computed(() => {
  const f = activeTableFilter.value
  let list = products.value
  
  if (f === 'ALL') {
    list = list.filter(p => p.status === 1 || p.status === 'ACTIVE')
  } else if (f === 'INACTIVE') {
    list = list.filter(p => p.status === 0 || p.status === 'INACTIVE')
  } else {
    const catId = Number(f)
    list = list.filter(p => {
      if (p.status !== 1 && p.status !== 'ACTIVE') return false
      return Number(p.productCategoryId) === catId
    })
  }

  // 關鍵字搜尋（名稱、說明）
  if (filterParams.value.keyword.trim()) {
    const query = filterParams.value.keyword.trim().toLowerCase()
    list = list.filter(p => {
      const nameMatch = p.name ? p.name.toLowerCase().includes(query) : false
      const descMatch = p.description ? p.description.toLowerCase().includes(query) : false
      return nameMatch || descMatch
    })
  }

  // 日期篩選 (startDate)
  if (filterParams.value.startDate) {
    const sDate = new Date(filterParams.value.startDate + 'T00:00:00')
    list = list.filter(p => {
      if (!p.createdAt) return false
      return new Date(p.createdAt) >= sDate
    })
  }

  // 日期篩選 (endDate)
  if (filterParams.value.endDate) {
    const eDate = new Date(filterParams.value.endDate + 'T23:59:59')
    list = list.filter(p => {
      if (!p.createdAt) return false
      return new Date(p.createdAt) <= eDate
    })
  }

  return list
})

// 分頁狀態
// [響應式狀態] currentPage (currentpage)
// 為什麼要這麼做：目前選取的資料，以便進行單一資料展示、編輯或預約操作。
const currentPage = ref(1)
// [響應式狀態] itemsPerPage (itemsperpage)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const itemsPerPage = ref(10)

// [計算屬性] totalItems (totalitems)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const totalItems = computed(() => filteredTableProducts.value.length)
// [計算屬性] totalPages (totalpages)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const totalPages = computed(() => Math.ceil(totalItems.value / itemsPerPage.value) || 1)

// [計算屬性] paginatedProducts (paginatedproducts)
// 為什麼要這麼做：在架商品清單，提供頁面渲染商品列表的資料來源。
const paginatedProducts = computed(() => {
  const start = (currentPage.value - 1) * itemsPerPage.value
  const end = start + itemsPerPage.value
  return filteredTableProducts.value.slice(start, end)
})

// [計算屬性] visiblePages (visiblepages)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const visiblePages = computed(() => {
  const current = currentPage.value
  const total = totalPages.value
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => i + 1)
  }
  const pages = []
  pages.push(1)
  let start = Math.max(2, current - 2)
  let end = Math.min(total - 1, current + 2)
  if (current <= 4) {
    end = 5
  } else if (current >= total - 3) {
    start = total - 4
  }
  if (start > 2) {
    pages.push('...')
  }
  for (let i = start; i <= end; i++) {
    pages.push(i)
  }
  if (end < total - 1) {
    pages.push('...')
  }
  pages.push(total)
  return pages
})

// [功能方法] getProductImage (取得productimage)
// 為什麼要這麼做：向伺服器查詢並獲取指定資料，以供畫面渲染或後續邏輯判斷使用。
function getProductImage(p) {
  if (p.imageUrl) return formatImageUrl(p.imageUrl)
  return 'https://images.unsplash.com/photo-1583454110551-21f2fa2afe61?w=400&auto=format&fit=crop&q=60'
}

// [功能方法] uploadFile (uploadfile)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
async function uploadFile(event, mode) {
  const file = event.target.files[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    showToast('照片檔案不能超過 5MB', 'error')
    return
  }
  uploadingFile.value = true
  const res = await employeeStore.uploadImage(file)
  uploadingFile.value = false
  if (res.success) {
    if (mode === 'add') {
      productForm.value.imageUrl = res.url
    } else {
      editForm.value.imageUrl = res.url
    }
    showToast('圖片上傳成功')
  } else {
    showToast(res.message || '上傳失敗', 'error')
  }
}

// [功能方法] createProduct (建立product)
// 為什麼要這麼做：初始化一個全新資源或訂單物件，為使用者接下來的編輯與提交做好準備。
async function createProduct() {
  const categoryIdVal = productForm.value.productCategoryId === "" || productForm.value.productCategoryId === undefined ? null : productForm.value.productCategoryId
  const priceVal = productForm.value.price === "" || productForm.value.price === null ? 0 : Number(productForm.value.price)
  const stockVal = productForm.value.stock === "" || productForm.value.stock === null ? 0 : Number(productForm.value.stock)

  const payload = {
    name: productForm.value.name,
    price: priceVal,
    stock: stockVal,
    description: productForm.value.description || '',
    imageUrl: productForm.value.imageUrl || '',
    productCategoryId: categoryIdVal,
    classSize: '',
    isTrial: false,
    sessionsPerUnit: 1
  }

  const res = await employeeStore.createProduct(payload)
  if (res.success) {
    showToast('新商品上架成功！')
    showAddProduct.value = false
    productForm.value = { name: '', price: null, stock: null, description: '', imageUrl: '', productCategoryId: null }
    await employeeStore.loadAllProducts()
  } else {
    showToast(res.message || '新增商品失敗', 'error')
  }
}

// [功能方法] openEditModal (開啟編輯modal)
// 為什麼要這麼做：控制特定彈窗 (Modal) 或抽屜的顯示狀態，引導使用者進行下一步的核心操作。
function openEditModal(p) {
  editForm.value = {
    id: p.productId,
    name: p.name,
    price: p.price,
    stock: p.stock,
    description: p.description || '',
    imageUrl: p.imageUrl || '',
    productCategoryId: p.productCategoryId || null
  }
  showEditProduct.value = true
}

// [功能方法] updateProduct (更新product)
// 為什麼要這麼做：將使用者修改後的資料同步至伺服器或本地 State，完成狀態更新並提供正確的介面呈現。
async function updateProduct() {
  const res = await employeeStore.updateProduct(editForm.value.id, editForm.value)
  if (res.success) {
    showToast('商品資料更新成功')
    showEditProduct.value = false
    await employeeStore.loadAllProducts()
  } else {
    showToast(res.message || '更新失敗', 'error')
  }
}

// [功能方法] deleteProduct (刪除product)
// 為什麼要這麼做：呼叫後端刪除 API 移除指定資料，並在完成後重新整理列表以確保畫面步調一致。
async function deleteProduct(id) {
  if (!confirm('確認將此商品下架？會員將無法再線上購買此商品。')) return
  const res = await employeeStore.deleteProduct(id)
  if (res.success) {
    showToast('商品已成功下架')
    await employeeStore.loadAllProducts()
  } else {
    showToast(res.message || '下架失敗', 'error')
  }
}

// [功能方法] createCategory (建立category)
// 為什麼要這麼做：初始化一個全新資源或訂單物件，為使用者接下來的編輯與提交做好準備。
async function createCategory() {
  if (!newCategoryForm.value.name.trim()) return
  const res = await employeeStore.createProductCategory({ name: newCategoryForm.value.name })
  if (res.success) {
    showToast('商品分類新增成功')
    newCategoryForm.value.name = ''
  } else {
    showToast(res.message || '新增分類失敗', 'error')
  }
}

// [功能方法] deleteCategory (刪除category)
// 為什麼要這麼做：呼叫後端刪除 API 移除指定資料，並在完成後重新整理列表以確保畫面步調一致。
async function deleteCategory(cat) {
  if (!confirm(`確認刪除商品分類「${cat.name}」？`)) return
  const res = await employeeStore.deleteProductCategory(cat.productCategoryId)
  if (res.success) {
    showToast('分類已刪除')
  } else {
    showToast(res.message || '刪除分類失敗', 'error')
  }
}

// [功能方法] loadProducts (載入products)
// 為什麼要這麼做：向後端 API 請求最新數據，確保頁面載入或重新整理時，使用者能看到最即時且正確的資料。
async function loadProducts() {
  await employeeStore.loadAllProducts()
}

// [功能方法] loadCategories (載入categories)
// 為什麼要這麼做：向後端 API 請求最新數據，確保頁面載入或重新整理時，使用者能看到最即時且正確的資料。
async function loadCategories() {
  await employeeStore.loadProductCategories()
}

// [生命週期掛載] 組件初始化載入
// 為什麼要這麼做：在 Vue 元件完全掛載至網頁 DOM 後，立即向後端發送請求載入初始頁面資料，提供即時的視覺展現。
onMounted(() => {
  loadProducts()
  loadCategories()
})
</script>

<style scoped>
.admin-table table td {
  vertical-align: middle;
}
.product-name-cell .name-text {
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 0.25rem;
}
.product-name-cell .desc-text {
  font-size: 0.72rem;
  color: #64748b;
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dashboard-footer-blank {
  height: 8rem;
}
.slide-enter-active,
.slide-leave-active {
  transition: all 0.3s ease-out;
}
.slide-enter-from,
.slide-leave-to {
  transform: translateY(-20px);
  opacity: 0;
}

/* 共用分頁控制列與下拉選單樣式 */
.list-control-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.25rem;
  padding: 0.5rem 1rem;
  background: var(--bg-section, #f8fafc);
  border-radius: var(--radius-md, 8px);
  border: 1px solid var(--border, #e2e8f0);
}
.list-control-header.bg-none {
  background: none;
  border: none;
  padding: 0;
  margin-bottom: 0;
}
.total-count-text {
  font-size: 0.88rem;
  color: var(--text-secondary, #64748b);
  font-weight: 600;
}
.page-size-selector {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.88rem;
  color: var(--text-secondary, #64748b);
}
.select-sm {
  padding: 0.3rem 1.5rem 0.3rem 0.6rem;
  font-size: 0.85rem;
  border-radius: 6px;
  border: 1px solid var(--border, #e2e8f0);
  background-color: var(--bg-card, #ffffff);
  color: var(--text-primary);
  cursor: pointer;
}

/* 縮小分頁按鈕 */
.pagination .page-btn {
  width: 28px !important;
  height: 28px !important;
  font-size: 0.8rem !important;
  border-radius: 4px !important;
}
</style>
