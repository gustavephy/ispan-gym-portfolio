<template>
  <div class="page-container admin-dashboard">
    <!-- 統計數據卡片區 -->
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-icon-wrapper active-bg">
          <span class="stat-icon-dot active-dot"></span>
        </div>
        <div class="stat-info">
          <p class="stat-card-num">{{ statsData.activeCount }}</p>
          <p class="stat-card-label">進行中優惠券</p>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon-wrapper upcoming-bg">
          <span class="stat-icon-dot upcoming-dot"></span>
        </div>
        <div class="stat-info">
          <p class="stat-card-num">{{ statsData.upcomingCount }}</p>
          <p class="stat-card-label">待進行優惠券</p>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon-wrapper expired-bg">
          <span class="stat-icon-dot expired-dot"></span>
        </div>
        <div class="stat-info">
          <p class="stat-card-num">{{ statsData.expiredCount }}</p>
          <p class="stat-card-label">已失效優惠券</p>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon-wrapper total-bg">
          <span class="stat-icon-dot total-dot"></span>
        </div>
        <div class="stat-info">
          <p class="stat-card-num">{{ statsData.totalClaimedCount }}</p>
          <p class="stat-card-label">目前已發放張數</p>
        </div>
      </div>
    </div>

    <div class="page-header-row" style="display: flex; gap: 1rem; margin-bottom: 1.5rem;">
      <button class="btn btn-primary btn-sm" @click="showAddCoupon = !showAddCoupon">
        <span>{{ showAddCoupon ? '✕ 取消新增' : '＋ 新增優惠券' }}</span>
      </button>
    </div>

    <!-- 新增優惠券表單 -->
    <Transition name="slide">
      <AdminFormCard
        v-if="showAddCoupon"
        title="新增優惠券資料"
        helper="*所有欄位均為必填"
        @submit="createCoupon"
      >
        <div class="form-group">
          <label class="form-label">優惠券代碼 (限英文大寫與數字)</label>
          <input v-model="couponForm.code" class="form-input" placeholder="例如: FIT888" required />
        </div>
        <div class="form-group">
          <label class="form-label">促銷活動名稱</label>
          <input v-model="couponForm.name" class="form-input" placeholder="例如: 開幕慶狂歡折抵" required />
        </div>
        <div class="form-group">
          <label class="form-label">優惠券描述 / 使用說明</label>
          <input v-model="couponForm.description" class="form-input" placeholder="例如: 僅限實體商品滿1000元折抵，教練課除外" />
        </div>
        <div class="form-group">
          <label class="form-label">折扣類型</label>
          <select v-model="couponForm.type" class="form-input">
            <option value="CASH">現金折抵 (CASH)</option>
            <option value="PERCENT">百分比折抵 (PERCENT)</option>
          </select>
        </div>
        <div class="form-group">
          <label class="form-label">折抵數值</label>
          <input v-model.number="couponForm.value" type="number" step="0.01" class="form-input" placeholder="輸入金額(如 200) 或比例(如 0.9)" required />
        </div>
        <div class="form-group">
          <label class="form-label">消費門檻條件</label>
          <input v-model.number="couponForm.minSpend" type="number" class="form-input" min="0" placeholder="0 表示不設門檻" required />
        </div>
        <div class="form-group">
          <label class="form-label">發行總張數</label>
          <input v-model.number="couponForm.totalQuantity" type="number" class="form-input" min="1" placeholder="張數" required />
        </div>
        <div class="form-group">
          <label class="form-label">活動生效日期</label>
          <input v-model="couponForm.startDate" type="date" class="form-input" required />
        </div>
        <div class="form-group">
          <label class="form-label">活動截止日期</label>
          <input v-model="couponForm.endDate" type="date" class="form-input" required />
        </div>
        <div class="form-group">
          <label class="form-label">適用商品範圍</label>
          <select v-model="couponForm.applicableType" class="form-input">
            <option value="ALL">全部商品與方案適用</option>
            <option value="GOODS">僅限實體商品使用</option>
            <option value="COURSE">僅限教練課使用</option>
          </select>
        </div>

        <template #actions>
          <button type="submit" class="btn btn-primary">確認發佈發行</button>
        </template>
      </AdminFormCard>
    </Transition>

    <!-- 搜尋與篩選條件區 -->
    <SearchFilter 
      v-model="filterParams"
      keyword-label="優惠券搜尋"
      keyword-placeholder="搜尋代碼或名稱..."
      date-label="活動有效日期範圍"
      @search="handleSearch"
      @reset="resetFilters"
    />

    <!-- 狀態篩選快捷鍵 (Tabs) -->
    <CategoryFilter 
      v-model="filterParams.status"
      :options="statusTabsWithCount"
      label="快速狀態篩選："
    />

    <!-- 優惠券清單表格 -->
    <div class="admin-table card">
      <!-- 控制列 -->
      <div class="list-control-header bg-none" style="margin: 1rem 1rem 1.5rem 1rem; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 1rem;">
        <div style="display: flex; align-items: center; gap: 1.5rem; flex-wrap: wrap;">
          <span class="total-count-text">共 {{ totalElements }} 筆優惠券</span>
          <div class="page-size-selector">
            <label>顯示數量：</label>
            <select v-model.number="itemsPerPage" class="select-sm">
              <option :value="5">5 筆</option>
              <option :value="10">10 筆</option>
              <option :value="20">20 筆</option>
              <option :value="50">50 筆</option>
            </select>
          </div>
        </div>

        <!-- 分頁 (Top) -->
        <div class="pagination" v-if="totalPages > 1" style="margin: 0; display: flex; gap: 0.5rem;">
          <button type="button" class="page-btn" :disabled="currentPage === 1" @click="currentPage--">‹</button>
          <template v-for="n in visiblePages" :key="n">
            <span v-if="n === '...'" class="page-ellipsis">...</span>
            <button v-else type="button" :class="['page-btn', { active: currentPage === n }]" @click="currentPage = n">{{ n }}</button>
          </template>
          <button type="button" class="page-btn" :disabled="currentPage === totalPages" @click="currentPage++">›</button>
        </div>
      </div>

      <div class="coupon-table-wrap">
      <table style="table-layout: fixed; width: 100%;">
        <thead>
          <tr>
            <th style="width: 100px;">代碼</th>
            <th style="width: 180px;">促銷活動名稱</th>
            <th style="width: 140px;">優惠券描述</th>
            <th style="width: 110px;">折扣類型</th>
            <th style="width: 120px;">折抵金額/比例</th>
            <th style="width: 110px;">最低消費門檻</th>
            <th style="width: 110px;">適用範圍</th>
            <th style="width: 140px;">已領取/發行總數</th>
            <th style="width: 200px;">有效期限</th>
            <th style="width: 90px;">狀態</th>
            <th style="width: 280px;">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in paginatedCoupons" :key="c.couponId">
            <td class="coupon-code-val">{{ c.code }}</td>
            <td style="font-weight: 700;">{{ c.name }}</td>
            <td class="desc-cell" :title="c.description">{{ c.description || '無' }}</td>
            <td>
              <span :class="['type-tag', c.type === 'PERCENT' ? 'type-percent' : 'type-cash']">
                {{ c.type === 'PERCENT' ? '比例折抵' : '現金折抵' }}
              </span>
            </td>
            <td style="font-weight: 700; color: var(--accent);">
              {{ c.type === 'PERCENT' ? (c.value * 100) + '%' : 'NT$ ' + Number(c.value).toLocaleString() }}
            </td>
            <td>NT$ {{ Number(c.minSpend).toLocaleString() }}</td>
            <td>
              <span class="badge badge-scope">
                {{ c.applicableType === 'GOODS' ? '僅商品' : c.applicableType === 'COURSE' ? '僅課程' : '全部適用' }}
              </span>
            </td>
            <td>
              <div class="progress-bar-container">
                <div class="progress-bar" :style="{ width: (c.claimedQuantity / c.totalQuantity * 100) + '%' }"></div>
                <span class="progress-text">{{ c.claimedQuantity }} / {{ c.totalQuantity }}</span>
              </div>
            </td>
            <td class="coupon-date-text">
              {{ c.startDate }} 至 {{ c.endDate || '永久有效' }}
            </td>
            <td>
              <div class="status-cell-wrap">
                <span :class="['status-dot', (c.status === 'ACTIVE' || c.status === '1') ? 'active' : 'inactive']"></span>
                <span :class="['status-text-label', (c.status === 'ACTIVE' || c.status === '1') ? 'text-active' : 'text-inactive']">
                  {{ (c.status === 'ACTIVE' || c.status === '1') ? '發行中' : '已失效' }}
                </span>
              </div>
            </td>
            <td>
              <div class="action-buttons-wrap">
                <button class="btn btn-sm btn-edit" @click="openEditCouponModal(c)" title="編輯">編輯</button>
                <button v-if="c.status === 'ACTIVE' || c.status === '1'" class="btn btn-sm btn-edit" @click="distributeCouponToAll(c.couponId)" title="群發給全體會員">群發</button>
                <button v-if="c.status === 'ACTIVE' || c.status === '1'" class="btn btn-sm btn-edit" @click="openDistributeModal(c)" title="指定會員發放">指定發放</button>
                <button v-if="c.status === 'ACTIVE' || c.status === '1'" class="btn btn-sm btn-danger" @click="deleteCoupon(c.couponId)" title="下架">下架</button>
              </div>
            </td>
          </tr>
          <tr v-if="coupons.length === 0">
            <td colspan="11" style="text-align: center; padding: 3rem; color: #94a3b8;">
              目前未發佈任何促銷優惠活動
            </td>
          </tr>
        </tbody>
      </table>
      </div><!-- /.coupon-table-wrap -->

      <!-- 分頁 (Bottom) -->
      <div class="pagination" v-if="totalPages > 1" style="margin: 1rem 0; display: flex; justify-content: center; gap: 0.5rem;">
        <button type="button" class="page-btn" :disabled="currentPage === 1" @click="currentPage--">‹</button>
        <template v-for="n in visiblePages" :key="n">
          <span v-if="n === '...'" class="page-ellipsis">...</span>
          <button v-else type="button" :class="['page-btn', { active: currentPage === n }]" @click="currentPage = n">{{ n }}</button>
        </template>
        <button type="button" class="page-btn" :disabled="currentPage === totalPages" @click="currentPage++">›</button>
      </div>
    </div>

    <!-- 編輯優惠券 Modal -->
    <AppModal v-model:visible="showEditCoupon" title="修改優惠券內容" icon="" size="md">
      <form id="editCouponForm" @submit.prevent="updateCoupon">
        <div class="form-group">
          <label class="form-label">代碼 (無法修改)</label>
          <input v-model="editCouponForm.code" class="form-input" disabled />
        </div>
        <div class="form-group">
          <label class="form-label">活動名稱</label>
          <input v-model="editCouponForm.name" class="form-input" required />
        </div>
        <div class="form-group">
          <label class="form-label">優惠券描述 / 使用說明</label>
          <input v-model="editCouponForm.description" class="form-input" />
        </div>
        <div class="form-grid-responsive">
          <div class="form-group">
            <label class="form-label">折抵類型</label>
            <select v-model="editCouponForm.type" class="form-input">
              <option value="CASH">現金折抵</option>
              <option value="PERCENT">百分比折抵</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">折扣數值</label>
            <input v-model.number="editCouponForm.value" type="number" step="0.01" class="form-input" required />
          </div>
          <div class="form-group">
            <label class="form-label">最低消費門檻 (NT$)</label>
            <input v-model.number="editCouponForm.minSpend" type="number" class="form-input" min="0" required />
          </div>
          <div class="form-group">
            <label class="form-label">發行量張數</label>
            <input v-model.number="editCouponForm.totalQuantity" type="number" class="form-input" min="1" required />
          </div>
          <div class="form-group">
            <label class="form-label">生效日期</label>
            <input v-model="editCouponForm.startDate" type="date" class="form-input" required />
          </div>
          <div class="form-group">
            <label class="form-label">到期日期</label>
            <input v-model="editCouponForm.endDate" type="date" class="form-input" required />
          </div>
          <div class="form-group">
            <label class="form-label">適用商品範圍</label>
            <select v-model="editCouponForm.applicableType" class="form-input">
              <option value="ALL">全部商品適用</option>
              <option value="GOODS">僅限實體商品</option>
              <option value="COURSE">僅限課程方案</option>
            </select>
          </div>
        </div>
      </form>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="showEditCoupon = false">取消</button>
        <button type="submit" form="editCouponForm" class="btn btn-primary">保存修改活動</button>
      </template>
    </AppModal>

    <!-- 指定發放 Modal -->
    <AppModal v-model:visible="showDistributeModal" title="指定發放優惠券" icon="" size="md">
      <div v-if="distributeTarget" class="distribute-modal-body">
        <div class="distribute-coupon-info">
          <span class="distribute-coupon-code">{{ distributeTarget.code }}</span>
          <span class="distribute-coupon-name">{{ distributeTarget.name }}</span>
        </div>
        <p class="distribute-hint">輸入會員 ID，每行一個，或以逗號分隔：</p>
        <textarea
          id="distributeIdInput"
          v-model="distributeIdsText"
          class="form-input distribute-textarea"
          placeholder="例如：1, 2, 3&#10;或每行一個：&#10;1&#10;2&#10;3"
          rows="6"
        />
        <p class="distribute-count-hint" v-if="parsedMemberIds.length > 0">
          已輸入 <strong>{{ parsedMemberIds.length }}</strong> 位會員 ID
        </p>
      </div>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="showDistributeModal = false">取消</button>
        <button
          type="button"
          class="btn btn-primary"
          :disabled="parsedMemberIds.length === 0 || isDistributing"
          @click="distributeToMembers"
        >
          {{ isDistributing ? '發放中...' : `確認發放 (${parsedMemberIds.length} 人)` }}
        </button>
      </template>
    </AppModal>

    <div class="dashboard-footer-blank"></div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, inject, watch } from 'vue'
import api from '@/api/axios.instance.js'
import AppModal from '@/components/AppModal.vue'
import SearchFilter from '@/components/SearchFilter.vue'
import CategoryFilter from '@/components/CategoryFilter.vue'
import AdminFormCard from '@/components/AdminFormCard.vue'

const showToastInject = inject('showToast')
const showToast = (msg, type = 'success') => {
  if (showToastInject) {
    showToastInject(msg, type)
  } else {
    console.log(`[Toast] ${type}: ${msg}`)
  }
}

// [響應式狀態] showAddCoupon (顯示新增coupon)
// 為什麼要這麼做：開啟或切換特定版面、提示或按鈕的顯示狀態，實現動態的 UI 響應式交互。
const showAddCoupon = ref(false)
// [響應式狀態] showEditCoupon (顯示編輯coupon)
// 為什麼要這麼做：開啟或切換特定版面、提示或按鈕的顯示狀態，實現動態的 UI 響應式交互。
const showEditCoupon = ref(false)
// [響應式狀態] showDistributeModal (顯示distributemodal)
// 為什麼要這麼做：開啟或切換特定版面、提示或按鈕的顯示狀態，實現動態的 UI 響應式交互。
const showDistributeModal = ref(false)
// [響應式狀態] distributeTarget (distributetarget)
// 為什麼要這麼做：向伺服器查詢並獲取指定資料，以供畫面渲染或後續邏輯判斷使用。
const distributeTarget = ref(null)
// [響應式狀態] distributeIdsText (distributeidstext)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const distributeIdsText = ref('')
// [響應式狀態] isDistributing (isdistributing)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const isDistributing = ref(false)

// [計算屬性] parsedMemberIds (parsedmemberids)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const parsedMemberIds = computed(() => {
  if (!distributeIdsText.value.trim()) return []
  return distributeIdsText.value
    .split(/[,\n]+/)
    .map(s => parseInt(s.trim()))
    .filter(n => !isNaN(n) && n > 0)
})

// [響應式狀態] coupons (coupons)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const coupons = ref([])

// [響應式狀態] statsData (statsdata)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const statsData = ref({
  activeCount: 0,
  upcomingCount: 0,
  expiredCount: 0,
  totalClaimedCount: 0
})

// [響應式狀態] filterParams (篩選params)
// 為什麼要這麼做：依據指定屬性將不符合條件的陣列元素濾除，確保畫面只呈現使用者感興趣的資料。
const filterParams = ref({
  keyword: '',
  status: '',
  startDate: '',
  endDate: ''
})

const statusTabs = [
  { label: '全部', value: '' },
  { label: '進行中', value: 'ONGOING' },
  { label: '待進行', value: 'UPCOMING' },
  { label: '已失效', value: 'EXPIRED' },
  { label: '已下架', value: 'INACTIVE' }
]

// [計算屬性] statusTabsWithCount (statustabswithcount)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const statusTabsWithCount = computed(() => {
  return statusTabs.map(t => {
    const count = getTabCount(t.value)
    return {
      value: t.value,
      label: count !== null && count > 0 ? `${t.label} (${count})` : t.label
    }
  })
})

// [功能方法] getTabCount (取得tabcount)
// 為什麼要這麼做：向伺服器查詢並獲取指定資料，以供畫面渲染或後續邏輯判斷使用。
function getTabCount(value) {
  if (value === 'ONGOING') return statsData.value.activeCount
  if (value === 'UPCOMING') return statsData.value.upcomingCount
  if (value === 'EXPIRED') return statsData.value.expiredCount
  return null
}

// 分頁與監聽
// [響應式狀態] currentPage (currentpage)
// 為什麼要這麼做：目前選取的資料，以便進行單一資料展示、編輯或預約操作。
const currentPage = ref(1)
// [響應式狀態] itemsPerPage (itemsperpage)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const itemsPerPage = ref(10)
const totalElements = ref(0)
// [計算屬性] totalPages (totalpages)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const totalPages = computed(() => Math.ceil(totalElements.value / itemsPerPage.value) || 1)
// [計算屬性] paginatedCoupons (paginatedcoupons)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const paginatedCoupons = computed(() => coupons.value)
// [計算屬性] visiblePages (visiblepages)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const visiblePages = computed(() => {
  const current = currentPage.value
  const total = totalPages.value
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const pages = []
  pages.push(1)
  let start = Math.max(2, current - 2)
  let end = Math.min(total - 1, current + 2)
  if (current <= 4) { end = 5 } else if (current >= total - 3) { start = total - 4 }
  if (start > 2) pages.push('...')
  for (let i = start; i <= end; i++) pages.push(i)
  if (end < total - 1) pages.push('...')
  pages.push(total)
  return pages
})

// [監聽器] 監聽特定狀態變化
// 為什麼要這麼做：當監聽的數據或屬性發生異動時，即時觸發與其關聯的連動業務邏輯、重新計算數值或重新載入資料。
watch(() => filterParams.value.status, () => {
  currentPage.value = 1
  handleSearch()
})

watch([currentPage, itemsPerPage], () => {
  loadCoupons()
})

// [響應式狀態] couponForm (couponform)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const couponForm = ref({
  code: '',
  name: '',
  description: '',
  type: 'CASH',
  value: null,
  minSpend: 0,
  totalQuantity: 100,
  startDate: new Date().toISOString().split('T')[0],
  endDate: new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString().split('T')[0],
  applicableType: 'ALL'
})

// [響應式狀態] editCouponForm (編輯couponform)
// 為什麼要這麼做：開啟編輯模式或彈窗，並預先載入欲修改項目的現有資料，提供良好的使用者修改體驗。
const editCouponForm = ref({
  id: null, code: '', name: '', description: '', type: 'CASH', value: null, minSpend: 0, totalQuantity: 100,
  startDate: '', endDate: '', applicableType: 'ALL'
})

// [功能方法] loadStats (載入stats)
// 為什麼要這麼做：向後端 API 請求最新數據，確保頁面載入或重新整理時，使用者能看到最即時且正確的資料。
async function loadStats() {
  try {
    const res = await api.get('/api/admin/coupons/stats')
    if (res.data) {
      statsData.value = res.data
    }
  } catch (e) {
    console.error('Failed to load stats', e)
  }
}

// [功能方法] loadCoupons (載入coupons)
// 為什麼要這麼做：向後端 API 請求最新數據，確保頁面載入或重新整理時，使用者能看到最即時且正確的資料。
async function loadCoupons() {
  try {
    const params = {
      page: currentPage.value - 1,
      size: itemsPerPage.value
    }
    if (filterParams.value.keyword) params.keyword = filterParams.value.keyword
    if (filterParams.value.status) params.status = filterParams.value.status
    if (filterParams.value.startDate) params.startDate = filterParams.value.startDate
    if (filterParams.value.endDate) params.endDate = filterParams.value.endDate

    const res = await api.get('/api/admin/coupons', { params })
    coupons.value = res.data?.content || []
    totalElements.value = res.data?.totalElements || 0
  } catch (e) {
    console.error('Failed to load coupons', e)
  }
}

// [功能方法] handleSearch (處理搜尋)
// 為什麼要這麼做：響應使用者的點擊、輸入或提交等操作行為，執行相對應的業務流程邏輯。
function handleSearch() {
  currentPage.value = 1
  loadCoupons()
}

// [功能方法] resetFilters (重置filters)
// 為什麼要這麼做：將所有篩選條件、分頁或表單狀態還原至預設初值，解決狀態殘留引起的查詢問題。
function resetFilters() {
  filterParams.value = {
    keyword: '',
    status: '',
    startDate: '',
    endDate: ''
  }
  currentPage.value = 1
  loadCoupons()
}

// [功能方法] createCoupon (建立coupon)
// 為什麼要這麼做：初始化一個全新資源或訂單物件，為使用者接下來的編輯與提交做好準備。
async function createCoupon() {
  try {
    await api.post('/api/admin/coupons', couponForm.value)
    showToast('優惠券發放成功！')
    showAddCoupon.value = false
    couponForm.value = {
      code: '', name: '', description: '', type: 'CASH', value: null, minSpend: 0, totalQuantity: 100,
      startDate: new Date().toISOString().split('T')[0],
      endDate: new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString().split('T')[0],
      applicableType: 'ALL'
    }
    await loadCoupons()
    await loadStats()
  } catch (e) {
    showToast(e.response?.data?.message || '發放失敗', 'error')
  }
}

// [功能方法] distributeCouponToAll (distributecoupontoall)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
async function distributeCouponToAll(id) {
  if (!confirm('確定要將此優惠券群發給全體學員嗎？')) return
  try {
    const res = await api.post(`/api/admin/coupons/${id}/distribute-all`)
    showToast(res.data.message || '發送成功！')
    await loadCoupons()
    await loadStats()
  } catch (e) {
    showToast(e.response?.data?.message || '群發失敗', 'error')
  }
}

// [功能方法] openDistributeModal (開啟distributemodal)
// 為什麼要這麼做：控制特定彈窗 (Modal) 或抽屜的顯示狀態，引導使用者進行下一步的核心操作。
function openDistributeModal(coupon) {
  distributeTarget.value = coupon
  distributeIdsText.value = ''
  showDistributeModal.value = true
}

// [功能方法] distributeToMembers (distributetomembers)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
async function distributeToMembers() {
  if (parsedMemberIds.value.length === 0) return
  isDistributing.value = true
  try {
    const res = await api.post(
      `/api/admin/coupons/${distributeTarget.value.couponId}/distribute`,
      parsedMemberIds.value
    )
    showToast(res.data.message || '指定發放成功！')
    showDistributeModal.value = false
    await loadCoupons()
    await loadStats()
  } catch (e) {
    const errorMsg = e.response?.data?.message || e.message || '發放失敗，請確認會員 ID 是否正確'
    showToast(errorMsg, 'error')
  } finally {
    isDistributing.value = false
  }
}

// [功能方法] openEditCouponModal (開啟編輯couponmodal)
// 為什麼要這麼做：控制特定彈窗 (Modal) 或抽屜的顯示狀態，引導使用者進行下一步的核心操作。
function openEditCouponModal(coupon) {
  editCouponForm.value = {
    id: coupon.couponId,
    code: coupon.code,
    name: coupon.name,
    description: coupon.description || '',
    type: coupon.type,
    value: coupon.value,
    minSpend: coupon.minSpend,
    totalQuantity: coupon.totalQuantity,
    startDate: coupon.startDate,
    endDate: coupon.endDate,
    applicableType: coupon.applicableType || 'ALL'
  }
  showEditCoupon.value = true
}

// [功能方法] updateCoupon (更新coupon)
// 為什麼要這麼做：將使用者修改後的資料同步至伺服器或本地 State，完成狀態更新並提供正確的介面呈現。
async function updateCoupon() {
  try {
    await api.put(`/api/admin/coupons/${editCouponForm.value.id}`, editCouponForm.value)
    showToast('優惠券更新成功！')
    showEditCoupon.value = false
    await loadCoupons()
    await loadStats()
  } catch (e) {
    showToast(e.response?.data?.message || '更新失敗，請稍後再試', 'error')
  }
}

// [功能方法] deleteCoupon (刪除coupon)
// 為什麼要這麼做：呼叫後端刪除 API 移除指定資料，並在完成後重新整理列表以確保畫面步調一致。
async function deleteCoupon(id) {
  if (!confirm('確定要下架此優惠券嗎？下架後會員將無法再領取此優惠券。')) return
  try {
    await api.delete(`/api/admin/coupons/${id}`)
    showToast('優惠券下架成功！')
    await loadCoupons()
    await loadStats()
  } catch (e) {
    showToast('下架失敗，請稍後再試', 'error')
  }
}

// [生命週期掛載] 組件初始化載入
// 為什麼要這麼做：在 Vue 元件完全掛載至網頁 DOM 後，立即向後端發送請求載入初始頁面資料，提供即時的視覺展現。
onMounted(() => {
  loadCoupons()
  loadStats()
})
</script>

<style scoped>
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1.25rem;
  margin-bottom: 1.5rem;
}
.stat-card {
  background: #ffffff;
  padding: 1.25rem;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.02);
  display: flex;
  align-items: center;
  gap: 1rem;
  transition: transform 0.2s, box-shadow 0.2s;
}
.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.05);
}
.stat-icon-wrapper {
  width: 48px;
  height: 48px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.active-bg { background-color: #ecfdf5; }
.upcoming-bg { background-color: #eff6ff; }
.expired-bg { background-color: #fef2f2; }
.total-bg { background-color: #faf5ff; }

.stat-icon-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
}
.active-dot { background-color: #10b981; }
.upcoming-dot { background-color: #3b82f6; }
.expired-dot { background-color: #ef4444; }
.total-dot { background-color: #a855f7; }

.stat-card-num {
  font-size: 1.625rem;
  font-weight: 800;
  color: #1e293b;
  line-height: 1.2;
  margin: 0;
}
.stat-card-label {
  font-size: 0.8rem;
  font-weight: 700;
  color: #64748b;
  margin: 0;
}

/* 狀態快捷分頁 Tabs */
.status-tabs-row {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1.25rem;
  border-bottom: 1px solid #e2e8f0;
  padding-bottom: 0.5rem;
}
.status-tab-btn {
  background: transparent;
  border: none;
  padding: 0.5rem 1rem;
  font-size: 0.9rem;
  font-weight: 700;
  color: #64748b;
  cursor: pointer;
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.4rem;
  transition: all 0.2s ease;
}
.status-tab-btn:hover {
  color: #1e293b;
}
.status-tab-btn.active-tab {
  color: #4f46e5;
}
.status-tab-btn.active-tab::after {
  content: '';
  position: absolute;
  bottom: -0.5rem;
  left: 0;
  right: 0;
  height: 3px;
  background-color: #4f46e5;
  border-radius: 9999px;
}
.tab-badge {
  background-color: #f1f5f9;
  color: #64748b;
  font-size: 0.72rem;
  padding: 0.1rem 0.4rem;
  border-radius: 999px;
  font-weight: 800;
}
.active-tab .tab-badge {
  background-color: #eeebff;
  color: #4f46e5;
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

/* 指定發放 Modal 內容 */
.distribute-modal-body {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.distribute-coupon-info {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.6rem 0.9rem;
  background: #f1f5f9;
  border-radius: 8px;
}
.distribute-coupon-code {
  font-size: 0.85rem;
  font-weight: 800;
  color: #4f46e5;
  background: #eeebff;
  padding: 0.2rem 0.5rem;
  border-radius: 4px;
  letter-spacing: 0.05em;
}
.distribute-coupon-name {
  font-size: 0.9rem;
  font-weight: 600;
  color: #334155;
}
.distribute-hint {
  font-size: 0.85rem;
  color: #64748b;
  margin: 0;
}
.distribute-textarea {
  font-family: monospace;
  font-size: 0.9rem;
  resize: vertical;
  min-height: 120px;
}
.distribute-count-hint {
  font-size: 0.82rem;
  color: #10b981;
  margin: 0;
}
.distribute-count-hint strong {
  font-weight: 800;
}

/* 共用分頁樣式 */
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
/* 分頁按鈕由全域 main.css .page-btn 統一管理 */
</style>
