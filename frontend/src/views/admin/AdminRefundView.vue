<script setup>
import { ref, onMounted } from 'vue'
import { getPendingRefunds, completeRefund as apiCompleteRefund } from '@/api/adminOrder.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'

const refunds = ref([])
const loading = ref(true)
const listMessage = ref('')
const completingId = ref(null)

function formatDate(isoString) {
  return new Date(isoString).toLocaleString('zh-TW', { hour12: false })
}

async function fetchRefunds() {
  loading.value = true
  try {
    const res = await getPendingRefunds()
    refunds.value = res.data
  } finally {
    loading.value = false
  }
}

async function handleComplete(refund) {
  const confirmed = window.confirm(
    `確定要完成訂單 ${refund.merchantTradeNo} 的退款嗎？金額 NT$ ${Number(refund.amount).toLocaleString()}，此動作無法復原。`,
  )
  if (!confirmed) return

  listMessage.value = ''
  completingId.value = refund.refundId
  try {
    await apiCompleteRefund(refund.refundId)
    await fetchRefunds()
  } catch (err) {
    const apiError = err
    if (isInlineError(apiError)) {
      listMessage.value = apiError.message
    } else {
      notifyApiError(apiError)
    }
  } finally {
    completingId.value = null
  }
}

onMounted(fetchRefunds)
</script>

<template>
  <div class="page-container">
    <span class="section-tag">ADMIN</span>
    <h2 class="section-title">退款處理</h2>
    <p class="section-desc">會員針對已付款訂單申請退款後，會出現在這裡，確認退款完成後按下「完成退款」。</p>

    <OperationMessage :message="listMessage" />

    <div v-if="loading" class="spinner"></div>

    <template v-else>
      <p v-if="refunds.length === 0" class="empty-state">目前沒有待處理的退款申請。</p>

      <table v-else class="slot-table">
        <thead>
          <tr>
            <th>訂單編號</th>
            <th>退款單號</th>
            <th>金額</th>
            <th>申請原因</th>
            <th>申請時間</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="refund in refunds" :key="refund.refundId">
            <td class="mono">{{ refund.merchantTradeNo }}</td>
            <td class="mono">{{ refund.refundNo }}</td>
            <td>NT$ {{ Number(refund.amount).toLocaleString() }}</td>
            <td>{{ refund.reason || '未填寫' }}</td>
            <td>{{ formatDate(refund.requestedAt) }}</td>
            <td>
              <button
                class="btn btn-accent"
                type="button"
                :disabled="completingId === refund.refundId"
                @click="handleComplete(refund)"
              >
                {{ completingId === refund.refundId ? '處理中…' : '完成退款' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </template>
  </div>
</template>

<style scoped>
.mono {
  font-family: ui-monospace, "SF Mono", "Cascadia Code", Consolas, monospace;
  font-size: 0.85rem;
}

.empty-state {
  color: var(--text-muted);
  padding: 2rem 0;
}

.slot-table {
  width: 100%;
  border-collapse: collapse;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.slot-table th,
.slot-table td {
  text-align: left;
  padding: 0.85rem 1.25rem;
  border-bottom: 1px solid var(--border);
}

.slot-table th {
  color: var(--text-secondary);
  font-size: 0.85rem;
  font-weight: 700;
  background: var(--bg-soft);
}

.slot-table tbody tr:last-child td {
  border-bottom: none;
}
</style>
