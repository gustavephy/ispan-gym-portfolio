<script setup>
import { ref, computed, onMounted } from 'vue'
import { getSubscriptionPlans, getMySubscription } from '@/api/subscription.js'
import { checkoutDirectSubscriptionWithEcpay } from '@/api/order.js'
import { submitEcpayForm } from '@/utils/ecpay.js'
import { useAuthStore } from '@/stores/auth.js'
import { useFeedback } from '@/composables/useFeedback.js'
import FeedbackToast from '@/components/FeedbackToast.vue'

const auth = useAuthStore()

const plans = ref([])
const mySubscription = ref(null)
const loading = ref(false)
const purchasingId = ref(null)

const { feedback, showFeedback } = useFeedback()

const statusLabels = {
  ACTIVE: '使用中',
  EXPIRED: '已到期',
  CANCELLED: '已取消',
}

const hasActiveSubscription = computed(() => mySubscription.value?.isActive === true)

function formatDate(isoString) {
  return new Date(isoString).toLocaleDateString('zh-TW')
}

async function loadPlans() {
  loading.value = true
  try {
    const res = await getSubscriptionPlans()
    plans.value = res.data
  } finally {
    loading.value = false
  }
}

async function loadMySubscription() {
  // 未登入者不查：這支 API 需要登入，401 會被 axios 攔截器當成 session 過期，
  // 強制登出並導向 /login，把原本可以匿名瀏覽方案的訪客也一併踢出去
  if (!auth.isLoggedIn) {
    mySubscription.value = null
    return
  }
  try {
    const res = await getMySubscription()
    mySubscription.value = res.data
  } catch {
    mySubscription.value = null // 尚無任何訂閱紀錄，後端回 404，屬正常情況
  }
}

async function purchase(plan) {
  if (!auth.isLoggedIn) {
    showFeedback('請先登入會員後再訂閱', 'error')
    return
  }
  purchasingId.value = plan.subscriptionPlanId
  try {
    const res = await checkoutDirectSubscriptionWithEcpay({
      subscriptionPlanId: plan.subscriptionPlanId,
      couponCode: null,
    })
    submitEcpayForm(res.data.ecpayForm)
    // 送出表單後瀏覽器即將離開本頁（導向綠界），故意不重設 purchasingId，避免使用者在轉導期間重複點擊
  } catch (err) {
    showFeedback(err.response?.data?.message || '訂閱失敗，請稍後再試', 'error')
    purchasingId.value = null
  }
}

// ── 配合新樣板的欄位與函式 ──

// 1. 當前選擇的方案詳情（預設取載入的第一個方案）
const currentPlanDetails = computed(() => {
  const plan = plans.value[0] || {}
  return {
    badge: '威力會員首選',
    name: plan.name || '月度暢遊暢練制',
    price: Number(plan.price || 980).toLocaleString(),
    unit: '月',
  }
})

// 2. 訂閱按鈕的 Loading 狀態
const submitting = computed(() => purchasingId.value !== null)

// 3. 目前會籍的截止日期
const subscriptionEndDate = computed(() => {
  return mySubscription.value ? formatDate(mySubscription.value.endDate) : ''
})

// 4. 送出訂閱的事件處理器
async function handleSubscribeSubmit() {
  const plan = plans.value[0]
  if (!plan) {
    showFeedback('目前無可用方案，請稍後再試', 'error')
    return
  }
  await purchase(plan)
}

onMounted(() => {
  loadPlans()
  loadMySubscription()
})
</script>

<template>
  <div class="page-container subscription-page">
    <!-- 頁面標題 -->
    <div class="section-header">
      <span class="section-tag">Premium Membership</span>
      <h1 class="section-title">訂閱方案</h1>
      <p class="section-desc">免綁約、彈性月繳！限大安店使用，無時長限制通行，立即開啟您的健身生活。</p>
    </div>

      <div class="sub-main">
          <!-- 唯一月卡訂閱方案卡片 - 緊湊淺色系 -->
          <div class="plans-grid single-plan-grid">
            <div class="plan-card selected recommended">
              <div class="recommended-badge">威力會員首選</div>
              <div class="plan-card-header">
                <div>
                  <h4 class="plan-card-name">訂閱方案</h4>
                  <span class="plan-rate-tag highlight">暢玩無極限</span>
                </div>
              </div>
              
              <div class="plan-cost-display">
                <span class="currency">NT$</span>
                <span class="cost-number">980</span>
                <span class="period">/ 月 (訂閱制，自動按月續約扣款)</span>
              </div>
              
              <p class="plan-card-desc">限大安店通用，適合有長期運動規劃、想要無拘無束自主鍛鍊的學員。</p>
              
              <!-- 方案專屬特點清單 -->
              <div class="plan-features-list">
                <div class="p-feature-item">
                  <div class="p-feature-text">
                    <h5>大安店不限次數通行</h5>
                    <p>限大安店使用，不分時段、不限時長自由入場，器材隨意使用，訓練不設限。</p>
                  </div>
                </div>
                <div class="p-feature-item">
                  <div class="p-feature-text">
                    <h5>隨時取消、免綁約負擔</h5>
                    <p>無痛按月訂閱，可隨時在個人中心一鍵暫停或終止訂閱，拒絕年約綑綁。</p>
                  </div>
                </div>
                <div class="p-feature-item">
                  <div class="p-feature-text">
                    <h5>首月贈送 InBody 體組成檢測</h5>
                    <p>使用精密儀器為您測量脂肪與肌肉數據，並由專業教練為您解說一堂課。</p>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 結帳按鈕 -->
          <button
            class="btn btn-primary btn-checkout-submit"
            :disabled="submitting || hasActiveSubscription"
            @click="handleSubscribeSubmit"
            :class="{ 'btn-disabled': hasActiveSubscription }"
          >
            {{ submitting ? '訂閱處理中...' : hasActiveSubscription ? '您已是訂閱會員' : '確認完成線上訂閱與付費' }}
          </button>
          <p v-if="hasActiveSubscription" class="active-sub-note">
            您已擁有有效的月卡會籍（效期至 {{ subscriptionEndDate }}），無需重複訂閱。
          </p>
          <p v-else class="security-note">256-bit SSL 安全加密保障</p>
      </div>
  </div>
</template>

<style scoped>
.subscription-page {
  padding-bottom: 3rem;
}

.sub-layout {
  display: grid;
  grid-template-columns: 1fr 350px; /* Reduced width of sidebar */
  gap: 1.8rem;
  align-items: start;
}

.sub-main {
  display: flex;
  flex-direction: column;
  gap: 1.2rem;
}

/* Light-themed VIP card matching the overall system aesthetics */
.plans-grid.single-plan-grid {
  grid-template-columns: 1fr;
}
.plan-card.selected.recommended {
  position: relative;
  background: var(--bg-card);
  border: 2px solid var(--primary);
  color: var(--text-primary);
  padding: 1.5rem 1.5rem; /* Reduced padding */
  border-radius: var(--radius-lg);
  box-shadow: 0 8px 24px rgba(46, 92, 69, 0.04);
  overflow: hidden;
  transition: all 0.3s ease;
}
.recommended-badge {
  position: absolute;
  top: 15px;
  right: 20px;
  background: var(--primary);
  color: #ffffff;
  font-size: 0.7rem;
  font-weight: 700;
  padding: 0.2rem 0.6rem;
  border-radius: 50px;
  box-shadow: 0 2px 6px rgba(46, 92, 69, 0.1);
}
.plan-card-header {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}
.plan-type-icon {
  font-size: 1.6rem;
}
.plan-card-name {
  font-size: 1.2rem;
  font-weight: 800;
  color: var(--primary-dark);
  margin: 0;
}
.plan-rate-tag.highlight {
  display: inline-block;
  background: rgba(224, 122, 95, 0.1);
  color: #e07a5f;
  font-size: 0.7rem;
  font-weight: 800;
  padding: 0.1rem 0.4rem;
  border-radius: 4px;
  margin-top: 0.2rem;
}
.plan-cost-display {
  margin: 0.8rem 0;
  display: flex;
  align-items: baseline;
}
.plan-cost-display .currency {
  font-size: 1.1rem;
  color: var(--primary);
  font-weight: 700;
  margin-right: 0.2rem;
}
.plan-cost-display .cost-number {
  font-size: 2.5rem;
  color: var(--primary-dark);
  font-weight: 900;
  line-height: 1;
  margin-right: 0.4rem;
}
.plan-cost-display .period {
  color: var(--text-secondary);
  font-size: 0.85rem;
}
.plan-card-desc {
  font-size: 0.88rem;
  color: var(--text-secondary);
  line-height: 1.5;
  margin: 0 0 1.2rem 0;
}

/* Feature grid list inside Light VIP card - Compact */
.plan-features-list {
  display: grid;
  grid-template-columns: 1fr;
  gap: 0.8rem;
  border-top: 1px solid var(--border);
  padding-top: 1.2rem;
}
.p-feature-item {
  display: flex;
  align-items: center; /* Center aligned for better single-line look */
  gap: 0.8rem;
}
.p-feature-icon {
  font-size: 1.2rem;
  background: rgba(46, 92, 69, 0.04);
  padding: 0.4rem;
  border-radius: 8px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(46, 92, 69, 0.06);
  color: var(--primary);
}
.p-feature-text h5 {
  margin: 0;
  font-size: 0.9rem;
  font-weight: 700;
  color: var(--primary-dark);
}
.p-feature-text p {
  margin: 0;
  font-size: 0.8rem;
  color: var(--text-secondary);
  line-height: 1.3;
}

/* Payment Panel */
.payment-panel {
  padding: 1.2rem 1.5rem;
  background: var(--bg-card);
  border-radius: var(--radius-lg);
  border: 1px solid var(--border);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.02);
}
.panel-header {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  margin-bottom: 1rem;
  border-bottom: 1px solid var(--border);
  padding-bottom: 0.6rem;
}
.panel-header h3 {
  font-size: 1.15rem;
  font-weight: 800;
  color: var(--primary-dark);
  margin: 0;
}
.panel-icon {
  font-size: 1.2rem;
}

/* ECPay info banner */
.ecpay-banner {
  display: flex;
  flex-direction: column;
  gap: 0.9rem;
  background: rgba(46, 92, 69, 0.03);
  border: 1.5px solid rgba(46, 92, 69, 0.14);
  border-radius: var(--radius-md);
  padding: 1rem 1.2rem;
}
.ecpay-logo-wrap {
  display: flex;
  align-items: center;
  gap: 0.8rem;
}
.ecpay-icon {
  font-size: 2rem;
  line-height: 1;
}
.ecpay-name {
  font-size: 1rem;
  font-weight: 800;
  color: var(--primary-dark);
}
.ecpay-tag {
  font-size: 0.72rem;
  color: var(--text-muted);
  margin-top: 0.1rem;
}
.ecpay-methods {
  display: flex;
  flex-wrap: wrap;
  gap: 0.45rem;
}
.method-chip {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--primary-dark);
  background: rgba(46, 92, 69, 0.07);
  border: 1px solid rgba(46, 92, 69, 0.12);
  border-radius: 50px;
  padding: 0.2rem 0.65rem;
  white-space: nowrap;
}

/* Sidebar Order Summary */
.summary-card {
  padding: 1.5rem;
  background: var(--bg-card);
  border-radius: var(--radius-lg);
  border: 1px solid var(--border);
  position: sticky;
  top: 90px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.02);
}
.summary-title {
  font-size: 1.15rem;
  font-weight: 800;
  color: var(--primary-dark);
  margin-bottom: 1rem;
}
.summary-plan-info {
  background: rgba(46, 92, 69, 0.03);
  border-radius: var(--radius-md);
  padding: 1rem;
  margin-bottom: 1rem;
  border: 1px solid rgba(46, 92, 69, 0.05);
}
.summary-plan-badge {
  display: inline-block;
  background: var(--primary);
  color: #fff;
  font-size: 0.68rem;
  font-weight: 700;
  padding: 0.1rem 0.4rem;
  border-radius: 4px;
  margin-bottom: 0.4rem;
}
.summary-plan-name {
  font-size: 0.95rem;
  font-weight: 700;
  color: var(--primary-dark);
  margin-bottom: 0.3rem;
}
.summary-plan-price {
  font-size: 1.1rem;
}
.price-val {
  font-weight: 800;
  color: var(--primary);
}
.price-unit {
  font-size: 0.78rem;
  color: var(--text-muted);
  margin-left: 0.2rem;
}

.summary-divider {
  height: 1px;
  background: var(--border);
  margin: 1rem 0;
}

.perks-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}
.perks-list li {
  font-size: 0.82rem;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  gap: 0.4rem;
}
.check-icon {
  color: var(--primary);
  font-weight: 800;
}

.total-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 0.95rem;
  font-weight: 800;
  color: var(--primary-dark);
  margin-bottom: 1rem;
}
.total-price {
  font-size: 1.5rem;
  font-weight: 900;
  color: var(--accent, #e07a5f);
}

.btn-checkout-submit {
  width: 100%;
  padding: 0.9rem;
  font-size: 1rem;
  font-weight: 800;
  justify-content: center;
  border-radius: var(--radius-md);
  box-shadow: 0 4px 12px rgba(46, 92, 69, 0.2);
}
.btn-checkout-submit:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 5px 15px rgba(46, 92, 69, 0.25);
}
.security-note {
  text-align: center;
  font-size: 0.72rem;
  color: var(--text-muted);
  margin-top: 0.6rem;
  margin-bottom: 0;
}

@media (max-width: 992px) {
  .sub-layout {
    grid-template-columns: 1fr;
    gap: 1.5rem;
  }
  .payment-grid {
    grid-template-columns: 1fr; /* Stacked on mobile */
  }
  .summary-card {
    position: static;
  }
}
.btn-disabled {
  background-color: var(--border) !important;
  border-color: var(--border) !important;
  color: var(--text-muted) !important;
  cursor: not-allowed !important;
  box-shadow: none !important;
  transform: none !important;
}
.active-sub-note {
  text-align: center;
  font-size: 0.75rem;
  color: #10b981;
  font-weight: 700;
  margin-top: 0.6rem;
  margin-bottom: 0;
}
</style>
