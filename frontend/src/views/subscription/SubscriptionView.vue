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
  <div class="page-container">
    <span class="section-tag">MEMBERSHIP</span>
    <h2 class="section-title">會籍方案</h2>

    <div v-if="mySubscription" class="current-sub-card">
      <div>
        <p class="current-sub-label">目前訂閱</p>
        <h3>{{ mySubscription.planName }}</h3>
        <p class="current-sub-dates">
          {{ formatDate(mySubscription.startDate) }} ～ {{ formatDate(mySubscription.endDate) }}
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

    <div v-else class="plan-grid">
      <div v-for="plan in plans" :key="plan.subscriptionPlanId" class="plan-card">
        <h3 class="plan-name">{{ plan.name }}</h3>
        <p class="plan-desc">{{ plan.description }}</p>
        <p class="plan-price">NT$ {{ Number(plan.price).toLocaleString() }}</p>
        <p class="plan-duration">{{ plan.durationDays }} 天</p>
        <button
          class="btn btn-accent purchase-btn"
          :disabled="hasActiveSubscription || purchasingId !== null"
          @click="purchase(plan)"
        >{{ purchasingId === plan.subscriptionPlanId ? '處理中...' : '立即訂閱' }}</button>
      </div>
    </div>

    <FeedbackToast :feedback="feedback" />
  </div>
</template>

<style scoped>
.current-sub-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-section);
  padding: 1.25rem 1.5rem;
  margin-bottom: 1.5rem;
}
.current-sub-label {
  font-size: 0.78rem;
  color: var(--text-muted);
  margin-bottom: 0.2rem;
}
.current-sub-card h3 {
  font-size: 1.1rem;
  font-weight: 700;
}
.current-sub-dates {
  font-size: 0.82rem;
  color: var(--text-secondary);
  margin-top: 0.2rem;
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
.plan-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 1.25rem;
}
.plan-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-section);
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.plan-name {
  font-size: 1.05rem;
  font-weight: 700;
}
.plan-desc {
  font-size: 0.85rem;
  color: var(--text-secondary);
  min-height: 2.6em;
}
.plan-price {
  font-size: 1.5rem;
  font-weight: 900;
  color: var(--accent-hover);
  margin-top: 0.4rem;
}
.plan-duration {
  font-size: 0.8rem;
  color: var(--text-muted);
  margin-bottom: 0.6rem;
}
.purchase-btn {
  width: 100%;
}
</style>
