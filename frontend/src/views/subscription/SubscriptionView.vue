<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getSubscriptionPlans, getMySubscription } from '@/api/subscription.js'
import { checkoutDirectSubscriptionWithEcpay } from '@/api/order.js'
import { submitEcpayForm } from '@/utils/ecpay.js'
import { useAuthStore } from '@/stores/auth.js'
import { useFeedback } from '@/composables/useFeedback.js'
import FeedbackToast from '@/components/FeedbackToast.vue'
import AppButton from '@/components/AppButton.vue'

const auth = useAuthStore()
const router = useRouter()

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
  // 非會員與未登入者由 getMySubscription 內部擋下並回 { data: null }，這裡不必再判斷身分
  try {
    const res = await getMySubscription()
    mySubscription.value = res.data
  } catch {
    mySubscription.value = null // 尚無任何訂閱紀錄，後端回 404，屬正常情況
  }
}

async function purchase(plan) {
  if (!auth.isLoggedIn) {
    router.push({ name: 'login', query: { redirect: '/subscription' } })
    return
  }
  // 已登入但沒有會員身分（純員工／管理員）：會籍綁在 member 上，後端的
  // requireCurrentMemberId() 會回 401，而攔截器把 401 一律當成 session 過期，
  // 會把人直接登出並踢回登入頁。這裡先擋下並給明確訊息。
  if (!auth.isMember) {
    showFeedback('此帳號沒有會員身分，無法訂閱會籍', 'error')
    return
  }
  purchasingId.value = plan.subscriptionPlanId
  try {
    const res = await checkoutDirectSubscriptionWithEcpay({
      subscriptionPlanId: plan.subscriptionPlanId,
      couponCode: null,
    })
    // 記下這筆訂單，讓使用者從綠界返回 /orders 時主動查一次付款結果
    // （本機收不到綠界的 server-to-server 通知，見 OrderListView#syncPendingPayment）
    if (res.data.order?.orderId) {
      sessionStorage.setItem('pendingPaymentOrderId', String(res.data.order.orderId))
    }
    submitEcpayForm(res.data.ecpayForm)
    // 送出表單後瀏覽器即將離開本頁（導向綠界），故意不重設 purchasingId，避免使用者在轉導期間重複點擊
  } catch (err) {
    // 攔截器已正規化錯誤，訊息在 err.message；err.response 不存在（見 normalizeApiError）
    showFeedback(err.message || '訂閱失敗，請稍後再試', 'error')
    purchasingId.value = null
  }
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
      <h1 class="section-title">會籍方案</h1>
      <p class="section-desc">免綁約、彈性訂閱！不分時段通行，立即開啟您的健身生活。</p>
    </div>

    <!-- 當前會籍卡片 (已訂閱時顯示) -->
    <div v-if="mySubscription" class="current-sub-card">
      <div class="current-sub-info">
        <span class="current-sub-label">目前使用會籍</span>
        <h3 class="current-sub-title">{{ mySubscription.planName }}</h3>
        <p class="current-sub-dates">
          有效期限：{{ formatDate(mySubscription.startDate) }} ～ {{ formatDate(mySubscription.endDate) }}
        </p>
      </div>
      <span class="status-badge" :class="{ active: mySubscription.isActive }">
        {{ mySubscription.isActive ? statusLabels.ACTIVE : (statusLabels[mySubscription.status] || mySubscription.status) }}
      </span>
    </div>

    <p v-if="hasActiveSubscription" class="active-notice">
      您目前已有有效訂閱，到期後才能購買下一期方案。
    </p>

    <div v-if="loading" class="spinner"></div>

    <div v-else-if="plans.length === 0" class="empty-state">
      <p>目前沒有可購買的訂閱方案</p>
    </div>

    <div v-else class="sub-layout">
      <!-- 方案列表區 -->
      <div class="sub-main">
        <div class="plans-grid" :class="{ 'single-plan-grid': plans.length === 1 }">
          <div v-for="plan in plans" :key="plan.subscriptionPlanId" class="plan-card selected recommended">
            <div class="recommended-badge">{{ plan.durationDays }} 天方案</div>
            <div class="plan-card-header">
              <div>
                <h4 class="plan-card-name">{{ plan.name }}</h4>
                <span class="plan-rate-tag highlight">暢玩無極限</span>
              </div>
            </div>
            
            <div class="plan-cost-display">
              <span class="currency">NT$</span>
              <span class="cost-number">{{ Number(plan.price).toLocaleString() }}</span>
              <span class="period">/ {{ plan.durationDays }} 天</span>
            </div>
            
            <p class="plan-card-desc">{{ plan.description || '限大安店通用，適合有長期運動規劃、想要無拘無束自主鍛鍊的學員。' }}</p>
            
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
                  <h5>彈性續定負擔</h5>
                  <p>無痛訂閱，到期後可重新選擇或續定方案，拒絕長年約綑綁。</p>
                </div>
              </div>
            </div>

            <!-- 訂閱結帳按鈕 -->
            <AppButton
              variant="primary"
              class="btn-checkout-submit"
              :disabled="hasActiveSubscription"
              :loading="purchasingId === plan.subscriptionPlanId"
              @click="purchase(plan)"
              :class="{ 'btn-disabled': hasActiveSubscription }"
              style="margin-top: 1.5rem; width: 100%;"
            >
              {{ hasActiveSubscription ? '您已是訂閱會員' : '確認完成線上訂閱與付費' }}
            </AppButton>
          </div>
        </div>
      </div>

      <!-- 右側常見問題與說明 -->
      <div class="payment-panel">
        <div class="panel-header">
          <h3>訂閱常見問題</h3>
        </div>
        <div class="faq-list">
          <div class="faq-item">
            <h6>如何計算訂閱效期？</h6>
            <p>付費成功後效期立即生效。系統會根據您購買的方案天數（如 30 天、90 天或 365 天），自付費當天起算對應天數作為有效通行期。</p>
          </div>
          <div class="faq-item">
            <h6>可以重複訂閱嗎？</h6>
            <p>為避免效期重疊，系統限制每個會員在同一時間內只能擁有一份有效的會籍，到期後方可再次訂閱。</p>
          </div>
          <div class="faq-item">
            <h6>支援哪些付款方式？</h6>
            <p>目前與綠界金流 (ECPay) 整合，支援各家信用卡線上安全刷卡與付費。</p>
          </div>
        </div>
        <div class="ecpay-banner" style="margin-top: 1.5rem;">
          <p class="security-note" style="margin: 0; font-size: 0.8rem; color: var(--text-muted);">256-bit SSL 安全加密保障</p>
        </div>
      </div>
    </div>

    <FeedbackToast :feedback="feedback" />
  </div>
</template>

<style scoped>
.subscription-page {
  padding-bottom: 3rem;
}

.sub-layout {
  display: grid;
  grid-template-columns: 1fr 350px;
  gap: 1.8rem;
  align-items: start;
}

@media (max-width: 992px) {
  .sub-layout {
    grid-template-columns: 1fr;
  }
}

.sub-main {
  display: flex;
  flex-direction: column;
  gap: 1.2rem;
}

.plans-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1.5rem;
}

.plans-grid.single-plan-grid {
  grid-template-columns: 1fr;
}

.plan-card.selected.recommended {
  position: relative;
  background: var(--bg-card);
  border: 2px solid var(--primary);
  color: var(--text-primary);
  padding: 1.5rem;
  border-radius: var(--radius-lg);
  box-shadow: 0 8px 24px rgba(46, 92, 69, 0.04);
  overflow: hidden;
  transition: all 0.3s ease;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
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

.plan-features-list {
  display: grid;
  grid-template-columns: 1fr;
  gap: 0.8rem;
  border-top: 1px solid var(--border);
  padding-top: 1.2rem;
  flex-grow: 1;
}

.p-feature-item {
  display: flex;
  align-items: center;
  gap: 0.8rem;
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

.faq-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.faq-item h6 {
  margin: 0 0 0.3rem 0;
  font-size: 0.9rem;
  font-weight: 700;
  color: var(--text-primary);
}

.faq-item p {
  margin: 0;
  font-size: 0.8rem;
  color: var(--text-secondary);
  line-height: 1.4;
}

.current-sub-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f8faf9;
  border: 1px solid #dbe2df;
  border-radius: var(--radius-md);
  padding: 1.2rem 1.5rem;
  margin-bottom: 1.5rem;
}

.current-sub-label {
  font-size: 0.78rem;
  color: var(--text-muted);
  font-weight: 700;
  text-transform: uppercase;
  margin: 0 0 0.2rem 0;
}

.current-sub-title {
  margin: 0;
  font-size: 1.2rem;
  font-weight: 800;
  color: var(--primary-dark);
}

.current-sub-dates {
  margin: 0.2rem 0 0 0;
  font-size: 0.82rem;
  color: var(--text-secondary);
}

.status-badge {
  padding: 0.3rem 0.8rem;
  border-radius: 999px;
  font-size: 0.78rem;
  font-weight: 700;
  background: var(--bg-soft);
  color: var(--text-muted);
  flex-shrink: 0;
}

.status-badge.active {
  background: #d4f4dd;
  color: #157347;
}
.active-notice {
  font-size: 0.85rem;
  color: var(--accent-hover);
  background: var(--bg-soft);
  border-radius: var(--radius-sm);
  padding: 0.7rem 1rem;
  margin-bottom: 1.5rem;
}
.empty-state {
  text-align: center;
  padding: 4rem 0;
  color: var(--text-muted);
}
</style>
