<script setup>
defineOptions({ name: 'MemberCouponView' })
import { ref, onMounted, onActivated, computed } from 'vue'
import { useFeedback } from '@/composables/useFeedback.js'
import FeedbackToast from '@/components/FeedbackToast.vue'
import AppPagination from '@/components/AppPagination.vue'
import {
  getAvailableCounpons,
  getClaimCoupon,
  getMyCoupons,
} from '@/api/member/coupon.js'

const { feedback, showFeedback } = useFeedback(2500)

const activeTab = ref('claim')
const statusFilter = ref('UNUSED')
const claimableList = ref([])
const myCouponList = ref([])
const fmt = v => Number(v).toLocaleString('zh-TW')
const formatDate = d => d ? new Date(d).toLocaleDateString('zh-TW') : '永久有效'

const filteredMyList = computed(() => myCouponList.value)

const getDiscountLabel = (c) => {
  if (c.type === 'PERCENT') {
    const discountPercent = (100 - Number(c.value)) / 10
    return discountPercent.toString().replace(/\.0$/, '') + '折'
  }
  return fmt(c.value)
}

// 分頁控制狀態 (AppPagination 使用 1-indexed)
const claimPage = ref(1)
const claimTotalPages = ref(0)
const claimTotalElements = ref(0)
const myPage = ref(1)
const myTotalPages = ref(0)
const myTotalElements = ref(0)
const pageSize = 8 // 一頁顯示 8 個

async function loadClaimable(pageNum = 1) {
  try {
    // API 傳遞 0-indexed 的頁碼
    const res = await getAvailableCounpons({ page: pageNum - 1, size: pageSize })
    claimableList.value = (res.data.content || []).filter(c => c !== null)
    claimPage.value = (res.data.number || 0) + 1
    claimTotalPages.value = res.data.totalPages || 0
    claimTotalElements.value = res.data.totalElements || 0
  } catch (e) {
    console.error('Failed to load available coupons', e)
  }
}

async function loadMyCoupons(pageNum = 1) {
  try {
    // API 傳遞 0-indexed 的頁碼以及 status 參數直接由後端做狀態過濾
    const res = await getMyCoupons({ 
      page: pageNum - 1, 
      size: pageSize,
      status: statusFilter.value 
    })
    myCouponList.value = res.data.content || []
    myPage.value = (res.data.number || 0) + 1
    myTotalPages.value = res.data.totalPages || 0
    myTotalElements.value = res.data.totalElements || 0
  } catch (e) {
    console.error('Failed to load my coupons', e)
  }
}

async function handleClaim(couponId) {
  try {
    await getClaimCoupon(couponId)
    showFeedback('領取成功！已放入您的優惠券包', 'success')
    await loadClaimable(claimPage.value)
    await loadMyCoupons(myPage.value)
  } catch (e) {
    showFeedback(e.message || '領取失敗', 'error')
  }
}

// 狀態切換時將分頁重設為第一頁
function handleStatusFilter(status) {
  statusFilter.value = status
  loadMyCoupons(1)
}

onMounted(() => {
  loadClaimable(1)
  loadMyCoupons(1)
})

// DefaultLayout 用 <keep-alive> 快取這頁：結帳用掉券再回來會停在舊清單，重新抓。
// onActivated 在首次掛載也會觸發（那次由 onMounted 負責），用旗標略過。
let activatedBefore = false
onActivated(() => {
  if (activatedBefore) {
    loadClaimable(1)
    loadMyCoupons(1)
  }
  activatedBefore = true
})
</script>

<template>
    <div class="page-container">
        <div class="section-header">
            <span class="section-tag">My Coupons</span>
            <h1 class="section-title">優惠券專區</h1>
            <p class="section-desc">領取專屬優惠券，結帳享受超級折抵！</p>
        </div>

        <!-- Tabs 切換 -->
        <div class="coupon-tabs">
            <button :class="['tab-btn', { active: activeTab === 'claim' }]" @click="activeTab = 'claim'">
                領券中心
            </button>
            <button :class="['tab-btn', { active: activeTab === 'my' }]" @click="activeTab = 'my'">
                我的優惠券
            </button>
        </div>

        <!-- 領券中心 -->
        <div v-if="activeTab === 'claim'" class="claim-center">
            <div v-if="claimableList.length === 0" class="empty-state card">
                <p>太熱烈了！目前沒有可領取的優惠券</p>
            </div>

            <div v-else class="coupon-grid">
                <div v-for="c in claimableList" :key="c.couponId" class="coupon-card claimable">
                    <div class="coupon-left">
                        <div class="discount-value">
                            <span class="currency" v-if="c.type === 'CASH'">$</span>
                            <span class="num">{{ getDiscountLabel(c) }}</span>
                        </div>
                        <p class="min-spend">滿 NT$ {{ fmt(c.minSpend) }} 可用</p>
                    </div>
                    <div class="coupon-right">
                        <h3 class="coupon-name">{{ c.name }}</h3>
                        <p v-if="c.description" class="coupon-desc">{{ c.description }}</p>
                        <p class="coupon-code">代碼：<span>{{ c.code }}</span></p>
                        <p class="coupon-date">效期至 {{ c.endDate || '無期限' }}</p>
                        <button class="btn btn-accent claim-btn" @click="handleClaim(c.couponId)">
                            立即領取
                        </button>
                    </div>
                </div>
            </div>

            <!-- 領券中心分頁按鈕 (使用 AppPagination 組件) -->
            <AppPagination
                v-model="claimPage"
                :total-pages="claimTotalPages"
                :total-elements="claimTotalElements"
                :page-size="pageSize"
                @change="val => loadClaimable(val.page)"
            />
        </div>

    <!-- 我的優惠券 -->
    <div v-if="activeTab === 'my'" class="my-coupons">
      <div class="filter-bar">
        <button :class="['filter-btn', { active: statusFilter === 'UNUSED' }]" @click="handleStatusFilter('UNUSED')">未使用</button>
        <button :class="['filter-btn', { active: statusFilter === 'USED' }]" @click="handleStatusFilter('USED')">已使用</button>
        <button :class="['filter-btn', { active: statusFilter === 'EXPIRED' }]" @click="handleStatusFilter('EXPIRED')">已逾期</button>
      </div>

      <div v-if="filteredMyList.length === 0" class="empty-state card">
        <p>沒有找到符合狀態的優惠券</p>
      </div>

      <div v-else>
        <div class="coupon-grid">
          <div v-for="uc in filteredMyList" :key="uc.memberCouponId" :class="['coupon-card', { used: uc.status === 'USED', expired: uc.status === 'EXPIRED' }]">
            <div class="coupon-left">
              <div class="discount-value">
                <span class="currency" v-if="uc.coupon.type === 'CASH'">$</span>
                <span class="num">{{ getDiscountLabel(uc.coupon) }}</span>
              </div>
              <p class="min-spend">滿 NT$ {{ fmt(uc.coupon.minSpend) }} 可用</p>
            </div>
            <div class="coupon-right">
              <h3 class="coupon-name">{{ uc.coupon.name }}</h3>
              <p v-if="uc.coupon.description" class="coupon-desc">{{ uc.coupon.description }}</p>
              <p class="coupon-code">代碼：<span>{{ uc.coupon.code }}</span></p>
              <p class="coupon-date" v-if="uc.status === 'UNUSED'">效期至 {{ uc.coupon.endDate || '無期限' }}</p>
              <p class="coupon-date" v-if="uc.status === 'USED'">使用於 {{ formatDate(uc.usedAt) }}</p>
              <p class="coupon-date" v-if="uc.status === 'EXPIRED'">已於 {{ uc.coupon.endDate }} 逾期</p>
              <div class="status-badge-container">
                <span v-if="uc.status === 'UNUSED'" class="badge badge-primary">可使用</span>
                <span v-if="uc.status === 'USED'" class="badge badge-success">已核銷</span>
                <span v-if="uc.status === 'EXPIRED'" class="badge badge-danger">已過期</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 我的優惠券分頁按鈕 (使用 AppPagination 組件) -->
        <AppPagination
            v-model="myPage"
            :total-pages="myTotalPages"
            :total-elements="myTotalElements"
            :page-size="pageSize"
            @change="val => loadMyCoupons(val.page)"
        />
      </div>
    </div>
    <FeedbackToast :feedback="feedback" />
  </div>

</template>


<style scoped>
.coupon-tabs {
  display: flex;
  gap: 1rem;
  margin-bottom: 2rem;
  border-bottom: 2px solid #e2e8f0;
  padding-bottom: 0.5rem;
}
.tab-btn {
  background: none;
  border: none;
  font-size: 1.1rem;
  font-weight: 700;
  color: #64748b;
  cursor: pointer;
  padding: 0.5rem 1rem;
  position: relative;
  transition: var(--transition);
}
.tab-btn:hover {
  color: var(--accent);
}
.tab-btn.active {
  color: var(--accent);
}
.tab-btn.active::after {
  content: '';
  position: absolute;
  bottom: -0.6rem;
  left: 0;
  width: 100%;
  height: 3px;
  background: var(--accent);
  border-radius: 20px;
}

.filter-bar {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1.5rem;
}
.filter-btn {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  padding: 0.4rem 1rem;
  font-size: 0.88rem;
  border-radius: 20px;
  cursor: pointer;
  color: #475569;
  font-weight: 500;
  transition: var(--transition);
}
.filter-btn:hover, .filter-btn.active {
  background: var(--bg-soft);
  color: var(--primary-dark);
  border-color: transparent;
  font-weight: 600;
}

.empty-state {
  text-align: center;
  padding: 4rem 2rem;
  color: #64748b;
}

/* 優惠券卡片 */
.coupon-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr); /* 固定一排 4 個 */
  grid-auto-rows: 160px; /* 每排固定 160px，確保所有卡片大小一致 */
  gap: 1rem;
  margin-bottom: 2rem;
  min-height: 380px;
}
.coupon-card {
  display: flex;
  background: #ffffff;
  border-radius: 12px;
  border: 1px dashed #c0d8c0;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05);
  overflow: hidden;
  position: relative;
  height: 100%; /* 填滿 grid-auto-rows 的固定高度 */
  max-width: 100%;
}
.coupon-card::before, .coupon-card::after {
  content: '';
  position: absolute;
  left: 82px; /* 配合 coupon-left 的新寬度 90px */
  width: 16px;
  height: 16px;
  background: var(--bg-base, #f8f5f0);
  border-radius: 50%;
  z-index: 2;
}
.coupon-card::before {
  top: -8px;
}
.coupon-card::after {
  bottom: -8px;
}

.coupon-left {
  width: 90px; /* 從 120px 縮小至 90px */
  background: linear-gradient(135deg, var(--primary-dark) 0%, var(--primary) 100%);
  color: #ffffff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 0.5rem;
  border-right: 1px dashed rgba(255, 255, 255, 0.3);
  text-align: center;
}
.discount-value {
  display: flex;
  align-items: baseline;
  justify-content: center;
}
.discount-value .currency {
  font-size: 1rem;
  font-weight: 700;
}
.discount-value .num {
  font-size: 1.4rem; /* 從 2rem 縮小至 1.4rem */
  font-weight: 800;
}
.min-spend {
  font-size: 0.65rem;
  opacity: 0.9;
  margin-top: 0.25rem;
}

.coupon-right {
  flex: 1;
  padding: 0.85rem; /* 從 1.25rem 縮小至 0.85rem */
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}
.coupon-name {
  font-size: 0.9rem; /* 從 1rem 縮小至 0.9rem */
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 0.2rem;
}
.coupon-desc {
  font-size: 0.72rem; /* 從 0.78rem 縮小至 0.72rem */
  color: #64748b;
  margin-bottom: 0.4rem;
  line-height: 1.3;
}
.coupon-code {
  font-size: 0.8rem;
  color: #475569;
}
.coupon-code span {
  font-family: monospace;
  font-weight: 700;
  color: var(--accent);
  background: var(--bg-soft);
  padding: 0.1rem 0.4rem;
  border-radius: 4px;
}
.coupon-date {
  font-size: 0.75rem;
  color: #64748b;
  margin-top: 0.5rem;
}
.claim-btn {
  margin-top: 0.75rem;
  padding: 0.4rem 1rem;
  font-size: 0.85rem;
  width: max-content;
  border-radius: 20px;
}

/* 已使用與已過期的灰階樣式 */
.coupon-card.used, .coupon-card.expired {
  border-color: #e2e8f0;
}
.coupon-card.used .coupon-left, .coupon-card.expired .coupon-left {
  background: #cbd5e1;
  color: #64748b;
}
.coupon-card.used .coupon-right, .coupon-card.expired .coupon-right {
  opacity: 0.7;
}
/* 狀態徽章 */
.status-badge-container {
  margin-top: 0.5rem;
}
.badge-primary {
  background: var(--bg-soft);
  color: var(--accent);
  border: 1px solid var(--accent);
}
.badge-success {
  background: #e6f7ed;
  color: #2e7d4f;
  border: 1px solid #c2e9d2;
}
.badge-danger {
  background: #fdf2f2;
  color: #c0392b;
  border: 1px solid #f8d7da;
}
</style>