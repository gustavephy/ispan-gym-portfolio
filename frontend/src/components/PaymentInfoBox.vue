<script setup>
// ATM／超商代碼繳費資訊卡：訂單明細展開與付款結果頁共用。
import { ref, computed, onUnmounted } from 'vue'
import { formatDate } from '@/utils/orderStatus.js'

const props = defineProps({
  // OrderPaymentInfoResponse：{ choosePayment: 'ATM'|'CVS', bankCode, vAccount, paymentNo, expireAt }
  paymentInfo: { type: Object, required: true },
})

const heading = computed(() =>
  props.paymentInfo.choosePayment === 'ATM' ? '轉帳繳費資訊' : '超商代碼繳費資訊',
)

// ATM 給銀行代碼＋虛擬帳號；超商代碼給一組繳費代碼
const rows = computed(() => {
  const pi = props.paymentInfo
  if (pi.choosePayment === 'ATM') {
    return [
      { key: 'bank', label: '銀行代碼', value: pi.bankCode },
      { key: 'account', label: '轉帳帳號', value: pi.vAccount },
    ]
  }
  return [{ key: 'cvs', label: '超商代碼', value: pi.paymentNo }]
})

const copiedKey = ref(null)
let copiedTimer = null

async function copy(key, value) {
  try {
    await navigator.clipboard.writeText(String(value))
    copiedKey.value = key
    clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => {
      copiedKey.value = null
    }, 2000)
  } catch {
    // 非 https 或舊瀏覽器沒有 clipboard API；讓使用者自行選取
  }
}

onUnmounted(() => clearTimeout(copiedTimer))
</script>

<template>
  <div class="payment-info-box">
    <h4 class="detail-heading">{{ heading }}</h4>
    <p v-for="row in rows" :key="row.key" class="payment-info-row">
      <span>{{ row.label }}</span>
      <span class="payment-info-value">
        <strong>{{ row.value }}</strong>
        <button
          type="button"
          class="copy-btn"
          :class="{ copied: copiedKey === row.key }"
          :aria-label="`複製${row.label}`"
          @click="copy(row.key, row.value)"
        >
          {{ copiedKey === row.key ? '已複製' : '複製' }}
        </button>
      </span>
    </p>
    <p v-if="paymentInfo.expireAt" class="payment-info-expire">
      請於 {{ formatDate(paymentInfo.expireAt) }} 前完成繳費，逾期系統將自動取消訂單
    </p>
    <p class="payment-info-note">完成繳費後，請稍後點擊「查詢付款結果」確認是否已入帳</p>
  </div>
</template>

<style scoped>
.payment-info-box {
  margin-top: 0.75rem;
  padding: 0.9rem 1.1rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #fff8e6;
}
.detail-heading {
  font-size: 0.8rem;
  font-weight: 700;
  color: var(--text-muted);
  margin-bottom: 0.6rem;
}
.payment-info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 0.9rem;
  color: var(--text-secondary);
  margin: 0.3rem 0;
}
.payment-info-row strong {
  font-family: 'Courier New', monospace;
  font-size: 1rem;
  letter-spacing: 0.03em;
  color: var(--text-primary);
}
.payment-info-value {
  display: inline-flex;
  align-items: center;
  gap: 0.55rem;
}
.copy-btn {
  padding: 0.16rem 0.6rem;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--bg-section);
  color: var(--text-secondary);
  font-size: 0.72rem;
  font-weight: 700;
  white-space: nowrap;
  transition: var(--transition);
}
.copy-btn:hover {
  border-color: var(--accent);
  color: var(--accent-hover);
}
.copy-btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px rgba(201, 154, 91, 0.25);
}
.copy-btn.copied {
  border-color: #2f9e44;
  color: #2f9e44;
}
.payment-info-expire {
  margin-top: 0.5rem;
  font-size: 0.8rem;
  font-weight: 600;
  color: #a06400;
}
.payment-info-note {
  margin-top: 0.3rem;
  font-size: 0.76rem;
  color: var(--text-muted);
}
</style>
