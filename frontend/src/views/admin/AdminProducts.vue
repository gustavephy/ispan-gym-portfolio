<template>
  <div class="page-container admin-dashboard">
    <span class="section-tag">ADMIN</span>
    <h1 class="section-title">商品管理</h1>
    <p class="section-desc">管理補給商城的商品項目，包含新增、編輯商品資料與上下架操作。</p>

    <div class="page-header-row" style="display: flex; gap: 1rem; margin-bottom: 1.5rem;">
      <button class="btn btn-primary btn-sm" @click="showAddProduct = !showAddProduct">
        <span>{{ showAddProduct ? '✕ 取消新增' : '＋ 新增商品' }}</span>
      </button>
      <button class="btn btn-outline btn-sm" @click="showCategoryManager = true">
        <span>⚙ 管理商品分類</span>
      </button>
    </div>

    <!-- 新增商品表單 -->
    <Transition name="slide">
      <AdminFormCard
        v-if="showAddProduct"
        title="新增商品資料"
        helper="*所有欄位均為必填"
        @submit="createProduct"
      >
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

        <template #extra>
          <div class="form-group file-upload-group" style="width: 100%;">
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
          <div class="form-group" style="margin-top: 1rem; width: 100%;">
            <label class="form-label">詳細說明介紹</label>
            <textarea v-model="productForm.description" class="form-input" rows="3" placeholder="請填寫此項目的特色、規格或內容介紹..."></textarea>
          </div>
        </template>

        <template #actions>
          <button type="submit" class="btn btn-primary">確認發佈上架</button>
        </template>
      </AdminFormCard>
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
        <div class="pagination" v-if="totalPages > 1" style="margin: 0;">
          <button type="button" class="page-btn" :disabled="currentPage === 1" @click="currentPage--">‹</button>
          <template v-for="n in visiblePages" :key="n">
            <span v-if="n === '...'" class="page-ellipsis">...</span>
            <button v-else type="button" :class="['page-btn', { active: currentPage === n }]" @click="currentPage = n">{{ n }}</button>
          </template>
          <button type="button" class="page-btn" :disabled="currentPage === totalPages" @click="currentPage++">›</button>
        </div>
      </div>

      <div class="product-table-wrap">
      <table style="table-layout: fixed; width: 100%;">
        <thead>
          <tr>
            <th style="width: 60px;">ID</th>
            <th style="width: 90px;">商品圖片</th>
            <th style="width: 240px;">商品名稱 &amp; 說明</th>
            <th style="width: 100px;">所屬分類</th>
            <th style="width: 100px;">定價金額</th>
            <th style="width: 80px;">目前庫存</th>
            <th style="width: 90px;">狀態</th>
            <th style="width: 120px;">功能操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in paginatedProducts" :key="p.productId">
            <td class="hide-md">#{{ p.productId }}</td>
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
                <button class="btn btn-edit edit-btn-style btn-sm" @click="openEditModal(p)">編輯</button>
                <button v-if="p.status === 1 || p.status === 'ACTIVE'" class="btn btn-danger deactive-btn-style btn-sm" @click="deleteProduct(p.productId)">下架</button>
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
      <div class="pagination" v-if="totalPages > 1" style="margin: 1rem 0;">
        <button type="button" class="page-btn" :disabled="currentPage === 1" @click="currentPage--">‹</button>
        <template v-for="n in visiblePages" :key="n">
          <span v-if="n === '...'" class="page-ellipsis">...</span>
          <button v-else type="button" :class="['page-btn', { active: currentPage === n }]" @click="currentPage = n">{{ n }}</button>
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
import AppModal from '@/components/AppModal.vue'
import SearchFilter from '@/components/SearchFilter.vue'
import CategoryFilter from '@/components/CategoryFilter.vue'
import AdminFormCard from '@/components/AdminFormCard.vue'
import { formatImageUrl } from '@/utils/image.js'
import api from '@/api/axios.instance.js'

const showToastInject = inject('showToast', null)
const showToast = (msg, type = 'success') => {
  if (showToastInject) showToastInject(msg, type)
}

// 產品與分類列表狀態
const products = ref([])
const productCategories = ref([])

// [響應式狀態] showAddProduct (顯示新增product)
const showAddProduct = ref(false)
// [響應式狀態] showEditProduct (顯示編輯product)
const showEditProduct = ref(false)
// [響應式狀態] showCategoryManager (顯示categorymanager)
const showCategoryManager = ref(false)
// [響應式狀態] uploadingFile (uploadingfile)
const uploadingFile = ref(false)
// [響應式狀態] activeTableFilter (activetable篩選)
const activeTableFilter = ref('ALL')

// 搜尋與篩選狀態
const filterParams = ref({
  keyword: '',
  startDate: '',
  endDate: ''
})

// [功能方法] resetFilters (重置filters)
function resetFilters() {
  filterParams.value.keyword = ''
  filterParams.value.startDate = ''
  filterParams.value.endDate = ''
}

// 當篩選條件改變時，重置頁碼到第一頁
watch([activeTableFilter, () => filterParams.value.keyword, () => filterParams.value.startDate, () => filterParams.value.endDate], () => {
  currentPage.value = 1
})

const productForm = ref({ name: '', price: null, stock: null, description: '', imageUrl: '', productCategoryId: null })
const editForm = ref({ id: null, name: '', price: null, stock: null, description: '', imageUrl: '', productCategoryId: null })
const newCategoryForm = ref({ name: '', categoryType: 'PHYSICAL', iconUrl: '', sortOrder: 0 })

// 篩選出實體商品的分類
const goodsCategories = computed(() => {
  return productCategories.value.filter(c => c.name !== '月卡訂閱')
})

const tableFilters = computed(() => {
  const list = [
    { value: 'ALL', label: '全部實體商品' }
  ]
  goodsCategories.value.forEach(cat => {
    list.push({ value: String(cat.id || cat.productCategoryId), label: cat.name })
  })
  list.push({ value: 'INACTIVE', label: '已下架商品' })
  return list
})

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

  // 關鍵字搜尋
  if (filterParams.value.keyword.trim()) {
    const query = filterParams.value.keyword.trim().toLowerCase()
    list = list.filter(p => {
      const nameMatch = p.name ? p.name.toLowerCase().includes(query) : false
      const descMatch = p.description ? p.description.toLowerCase().includes(query) : false
      return nameMatch || descMatch
    })
  }

  // 日期篩選
  if (filterParams.value.startDate) {
    const sDate = new Date(filterParams.value.startDate + 'T00:00:00')
    list = list.filter(p => {
      if (!p.createdAt) return false
      return new Date(p.createdAt) >= sDate
    })
  }

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
const currentPage = ref(1)
const itemsPerPage = ref(10)

const totalItems = computed(() => filteredTableProducts.value.length)
const totalPages = computed(() => Math.ceil(totalItems.value / itemsPerPage.value) || 1)

const paginatedProducts = computed(() => {
  const start = (currentPage.value - 1) * itemsPerPage.value
  const end = start + itemsPerPage.value
  return filteredTableProducts.value.slice(start, end)
})

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

function getProductImage(p) {
  if (p.imageUrl) return formatImageUrl(p.imageUrl)
  return 'https://images.unsplash.com/photo-1583454110551-21f2fa2afe61?w=400&auto=format&fit=crop&q=60'
}

// 圖片上傳
async function uploadFile(event, mode) {
  const file = event.target.files[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    showToast('照片檔案不能超過 5MB', 'error')
    return
  }
  uploadingFile.value = true
  try {
    const formData = new FormData()
    formData.append('file', file)
    const res = await api.post('/api/admin/products/upload', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
    uploadingFile.value = false
    if (mode === 'add') {
      productForm.value.imageUrl = res.url
    } else {
      editForm.value.imageUrl = res.url
    }
    showToast('圖片上傳成功')
  } catch (e) {
    uploadingFile.value = false
    showToast('上傳圖片失敗', 'error')
  }
}

// 新增商品
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

  try {
    await api.post('/api/admin/products', payload)
    showToast('新商品上架成功！')
    showAddProduct.value = false
    productForm.value = { name: '', price: null, stock: null, description: '', imageUrl: '', productCategoryId: null }
    await loadProducts()
  } catch (e) {
    showToast('新增商品失敗', 'error')
  }
}

function openEditModal(p) {
  editForm.value = {
    id: p.productId || p.id,
    name: p.name,
    price: p.price,
    stock: p.stock,
    description: p.description || '',
    imageUrl: p.imageUrl || '',
    productCategoryId: p.productCategoryId || null
  }
  showEditProduct.value = true
}

// 更新商品
async function updateProduct() {
  try {
    await api.put(`/api/admin/products/${editForm.value.id}`, editForm.value)
    showToast('商品資料更新成功')
    showEditProduct.value = false
    await loadProducts()
  } catch (e) {
    showToast('更新失敗', 'error')
  }
}

// 刪除商品 (下架)
async function deleteProduct(id) {
  if (!confirm('確認將此商品下架？會員將無法再線上購買此商品。')) return
  try {
    await api.delete(`/api/admin/products/${id}`)
    showToast('商品已成功下架')
    await loadProducts()
  } catch (e) {
    showToast('下架失敗', 'error')
  }
}

// 新增分類
async function createCategory() {
  if (!newCategoryForm.value.name.trim()) return
  try {
    await api.post('/api/admin/product-categories', { name: newCategoryForm.value.name })
    showToast('商品分類新增成功')
    newCategoryForm.value.name = ''
    await loadCategories()
  } catch (e) {
    showToast('新增分類失敗', 'error')
  }
}

// 刪除分類
async function deleteCategory(cat) {
  const catId = cat.id || cat.productCategoryId
  if (!confirm(`確認刪除商品分類「${cat.name}」？`)) return
  try {
    await api.delete(`/api/admin/product-categories/${catId}`)
    showToast('分類已刪除')
    await loadCategories()
  } catch (e) {
    showToast('刪除分類失敗', 'error')
  }
}

// 載入商品列表
async function loadProducts() {
  try {
    // 打後台端點而不是前台的 /api/products——後者固定只回 ACTIVE，
    // 用它當資料來源會讓「已下架商品」篩選永遠是空的、下架後的商品也無從檢視
    const res = await api.get('/api/admin/products', {
      params: { page: 0, size: 1000 }
    })
    const data = res.data
    products.value = data?.content || data?.items || (Array.isArray(data) ? data : [])
  } catch (e) {
    showToast('載入商品列表失敗', 'error')
    console.error('loadProducts error:', e)
  }
}

// 載入商品分類列表
async function loadCategories() {
  try {
    const res = await api.get('/api/product-categories')
    const data = res.data
    productCategories.value = Array.isArray(data) ? data : (data?.content || data?.items || [])
  } catch (e) {
    showToast('載入商品分類失敗', 'error')
    console.error('loadCategories error:', e)
  }
}

onMounted(() => {
  loadProducts()
  loadCategories()
})
</script>

<style scoped>
/* ===== 後台通用資料表格樣式===== */
.admin-table {
  border-radius: 16px;
  padding: 0;
  overflow: hidden;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.02);
  border: 1px solid #e2e8f0;
  background: #ffffff;
}
.admin-table table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed !important;
}
.admin-table table th {
  background: #f8fafc;
  color: #475569;
  font-weight: 700;
  padding: 0.5rem 0.75rem;
  text-align: center;
  border-bottom: 1px solid #e2e8f0;
  font-size: 0.78rem;
  letter-spacing: 0.3px;
  vertical-align: middle;
}
.admin-table table td {
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid #f1f5f9;
  color: #334155;
  font-size: 0.78rem;
  vertical-align: middle;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.admin-table table tbody tr {
  height: 72px;
}
.admin-table table tr:hover td {
  background: #f8fafc;
}

/* 允許折行與顯示完整文字的欄位 */
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
  -webkit-box-orient: vertical;
}

.product-table-wrap {
  overflow-x: auto;
  min-height: 560px;
}

/* ===== 狀態標籤與徽章樣式 ===== */
.badge {
  display: inline-flex;
  align-items: center;
  padding: 0.25rem 0.5rem;
  font-size: 0.75rem;
  font-weight: 600;
  border-radius: 4px;
}
.badge-primary {
  background-color: var(--primary-light, #5a4a42);
  color: #ffffff;
}

/* ===== 狀態圓點樣式 ===== */
.status-cell-wrap {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
}
.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
}
.status-dot.active {
  background-color: #2ec4b6;
}
.status-dot.inactive {
  background-color: #e63946;
}
.status-text-label {
  font-weight: 600;
  font-size: 0.8rem;
}
.status-text-label.text-active {
  color: #2ec4b6;
}
.status-text-label.text-inactive {
  color: #e63946;
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

/* ===== 分頁按鈕樣式 (復刻自 AdminCourses) ===== */
.pagination {
  display: flex;
  justify-content: center;
  gap: 0.5rem;
}

.page-btn {
  width: 28px !important;
  height: 28px !important;
  border: 1.5px solid var(--border, #e8e0d9);
  border-radius: 4px !important;
  background: var(--bg-section, #ffffff);
  color: var(--text-secondary, #6b5f58);
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.8rem !important;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
}

.page-btn.active,
.page-btn:hover {
  background: var(--primary, #5a4a42) !important;
  color: #ffffff !important;
  border-color: transparent !important;
}

.page-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  background: var(--bg-section, #ffffff) !important;
  color: var(--text-secondary, #6b5f58) !important;
  border-color: var(--border, #e8e0d9) !important;
}

.page-ellipsis {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  color: var(--text-secondary, #6b5f58);
  font-size: 0.8rem;
}

/* ===== 後台共用按鈕與表單樣式 (復刻自 style.css & AdminCourses) ===== */
:deep(.btn), .btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  height: 38px !important;
  padding: 0 1.25rem !important;
  border-radius: 9999px; /* radius-xl */
  border: 1.5px solid transparent;
  cursor: pointer;
  font-size: 0.85rem;
  font-weight: 600;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
  letter-spacing: 0.03em;
  background-clip: padding-box;
  box-sizing: border-box !important;
}

:deep(.btn-primary), .btn-primary {
  background: var(--primary-dark, #3c302b) !important;
  color: #ffffff !important;
  border-color: rgba(255, 255, 255, 0.1) !important;
  box-shadow: none;
}

:deep(.btn-primary:hover), .btn-primary:hover {
  background: var(--primary, #5a4a42) !important;
  color: #ffffff !important;
  transform: translateY(-2px);
  box-shadow: 0 2px 8px rgba(60, 48, 43, 0.03);
}

:deep(.btn-outline), .btn-outline {
  background: transparent !important;
  border: 2px solid var(--primary-dark, #3c302b) !important;
  color: var(--primary-dark, #3c302b) !important;
}

:deep(.btn-outline:hover), .btn-outline:hover {
  background: var(--primary-dark, #3c302b) !important;
  color: #ffffff !important;
}

:deep(.btn-edit), .btn-edit, :deep(.edit-btn-style), .edit-btn-style {
  border: 1px solid var(--border, #e8e0d9) !important;
  background: var(--bg-section, #ffffff) !important;
  color: var(--text-secondary, #6b5f58) !important;
  font-weight: 600 !important;
}

:deep(.btn-edit:hover), .btn-edit:hover, :deep(.edit-btn-style:hover), .edit-btn-style:hover {
  background: var(--bg-soft, #f5efe9) !important;
  color: var(--primary-dark, #3c302b) !important;
}

:deep(.btn-danger), .btn-danger, :deep(.deactive-btn-style), .deactive-btn-style {
  border: 1px solid rgba(239, 68, 68, 0.2) !important;
  background: rgba(239, 68, 68, 0.02) !important;
  color: #c0392b !important;
  font-weight: 600 !important;
}

:deep(.btn-danger:hover), .btn-danger:hover, :deep(.deactive-btn-style:hover), .deactive-btn-style:hover {
  background: rgba(239, 68, 68, 0.08) !important;
  color: #962d22 !important;
}

/* 額外輔助小尺寸按鈕樣式 */
:deep(.btn-sm), .btn-sm {
  height: 30px !important;
  padding: 0 0.8rem !important;
  font-size: 0.78rem !important;
}

/* 表單與輸入框 */
:deep(.form-group), .form-group {
  margin-bottom: 0.75rem;
}

:deep(.form-label), .form-label {
  display: block;
  margin-bottom: 0.3rem;
  color: var(--text-secondary, #6b5f58);
  font-size: 0.85rem;
  font-weight: 500;
}

:deep(.form-input), .form-input {
  width: 100%;
  height: 38px !important;
  padding: 0 0.75rem;
  background: var(--bg-section, #ffffff);
  border: 1.5px solid var(--border, #e8e0d9);
  border-radius: 8px; /* radius-sm */
  color: var(--text-primary, #2b2320);
  font-size: 0.88rem;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
  box-sizing: border-box !important;
}

:deep(.form-input:focus), .form-input:focus {
  border-color: var(--primary-light, #5a4a42);
  box-shadow: 0 0 0 3px rgba(188, 163, 143, 0.15);
}

/* ===== 新增表單區塊與上傳樣式 ===== */
.add-form-card {
  margin-bottom: 2rem;
  border: 1px solid var(--border, #e8e0d9) !important;
  background: #ffffff;
  padding: 1.75rem;
  border-radius: 16px;
  animation: slideDown 0.3s ease-out;
}
@keyframes slideDown {
  from { opacity: 0; transform: translateY(-10px); }
  to   { opacity: 1; transform: translateY(0); }
}
.form-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.25rem;
  border-bottom: 1px solid var(--border, #e8e0d9);
  padding-bottom: 0.75rem;
}
.form-header h4 {
  font-size: 1.05rem;
  color: var(--primary-dark, #3c302b);
  font-weight: 700;
  margin: 0;
}
.form-helper {
  font-size: 0.75rem;
  color: #ef4444;
}
.form-grid-responsive {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1.25rem;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 1.5rem;
}
.file-upload-wrapper {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 0.75rem;
}
.custom-file-btn {
  background: var(--bg-soft, #f5efe9);
  border: 1px solid var(--border, #e8e0d9);
  color: var(--text-secondary, #6b5f58);
  padding: 0.5rem 1rem;
  border-radius: 8px;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.25s ease;
}
.custom-file-btn:hover {
  background: var(--primary-light, #5a4a42);
  color: #ffffff;
}
.hidden-file-input {
  display: none;
}
.url-input {
  margin-top: 0.5rem;
}
.form-preview-thumb {
  width: 90px;
  height: 90px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid var(--border, #e8e0d9);
}

/* ===== 分類管理表格樣式 ===== */
.category-manager-content table {
  width: 100%;
  border-collapse: collapse;
}
.category-manager-content table th {
  background: #f8fafc;
  color: #475569;
  font-weight: 700;
  padding: 0.6rem 0.5rem;
  border-bottom: 1.5px solid var(--border, #e8e0d9);
  font-size: 0.82rem;
}
.category-manager-content table td {
  padding: 0.6rem 0.5rem;
  border-bottom: 1px solid #f1f5f9;
  color: var(--text-primary, #2b2320);
  font-size: 0.85rem;
  vertical-align: middle;
}
.category-manager-content table tr:hover td {
  background: #f8fafc;
}
</style>
