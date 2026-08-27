<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import {
  getPendingRefunds,
  completeRefund as apiCompleteRefund,
  rejectRefund as apiRejectRefund,
} from '@/api/adminOrder.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'

const route = useRoute()

const refunds = ref([])
const loading = ref(true)
const listMessage = ref('')
const completingId = ref(null)
const rejectingId = ref(null)

// 從訂單管理頁點「前往退款處理」跳過來時，網址會帶 ?highlight=refundId，
// 把對應那一列標出來並捲動過去，讓管理員一眼找到要處理的那筆
const highlightId = ref(route.query.highlight ? Number(route.query.highlight) : null)

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
  if (highlightId.value) {
    await nextTick()
    document
      .querySelector(`[data-refund-id="${highlightId.value}"]`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
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

async function handleReject(refund) {
  // prompt 回傳 null 代表使用者按取消；空字串代表「確定要駁回但沒填原因」，兩者要分開處理
  const reason = window.prompt(
    `確定要駁回訂單 ${refund.merchantTradeNo} 的退款申請嗎？可以填寫駁回原因（選填）：`,
    '',
  )
  if (reason === null) return

  listMessage.value = ''
  rejectingId.value = refund.refundId
  try {
    await apiRejectRefund(refund.refundId, reason.trim())
    await fetchRefunds()
  } catch (err) {
    const apiError = err
    if (isInlineError(apiError)) {
      listMessage.value = apiError.message
    } else {
      notifyApiError(apiError)
    }
  } finally {
    rejectingId.value = null
  }
}

onMounted(fetchRefunds)
</script>

<template>
  <div class="page-container">
    <span class="section-tag">ADMIN</span>
    <h2 class="section-title">退款處理</h2>
    <p class="section-desc">
      會員針對已付款/已出貨/已完成訂單申請退款（含出貨後退貨）後會出現在這裡，
      確認退款完成後按下「完成退款」；理由不成立則按「駁回」，訂單狀態不受影響。
    </p>

    <OperationMessage :message="listMessage" />

    <div v-if="loading" class="spinner"></div>

    <template v-else>
      <p v-if="refunds.length === 0" class="empty-state">目前沒有待處理的退款申請。</p>

      <div v-else class="table-scroll">
        <table class="slot-table">
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
          <tr
            v-for="refund in refunds"
            :key="refund.refundId"
            :data-refund-id="refund.refundId"
            :class="{ 'highlight-row': refund.refundId === highlightId }"
          >
            <td class="mono">{{ refund.merchantTradeNo }}</td>
            <td class="mono">{{ refund.refundNo }}</td>
            <td>NT$ {{ Number(refund.amount).toLocaleString() }}</td>
            <td>{{ refund.reason || '未填寫' }}</td>
            <td>{{ formatDate(refund.requestedAt) }}</td>
            <td>
              <div class="action-cell">
                <RouterLink :to="`/admin/orders?keyword=${refund.merchantTradeNo}`" class="view-order-link">
                  查看訂單 →
                </RouterLink>
                <button
                  class="btn btn-outline"
                  type="button"
                  :disabled="completingId === refund.refundId || rejectingId === refund.refundId"
                  @click="handleReject(refund)"
                >
                  {{ rejectingId === refund.refundId ? '處理中…' : '駁回' }}
                </button>
                <button
                  class="btn btn-accent"
                  type="button"
                  :disabled="completingId === refund.refundId || rejectingId === refund.refundId"
                  @click="handleComplete(refund)"
                >
                  {{ completingId === refund.refundId ? '處理中…' : '完成退款' }}
                </button>
              </div>
            </td>
          </tr>
        </tbody>
        </table>
      </div>
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

/* 操作按鈕不再壓縮後，視窗窄時改由表格自己橫向捲動，不讓整頁跟著左右跑。
   圓角與邊框移到這層：表格自身的 overflow:hidden 會蓋掉捲軸 */
.table-scroll {
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.slot-table {
  width: 100%;
  min-width: 46rem;
  border-collapse: collapse;
  background: var(--bg-section);
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

.slot-table tbody tr.highlight-row {
  background: #fff3cd;
}

.action-cell {
  display: flex;
  align-items: center;
  gap: 1rem;
}

/* 這一格的按鈕文字都很短（駁回／完成退款），卻會因為 flex item 預設可縮到比內容還窄
   而被折成兩行。nowrap 保住文字、flex-shrink:0 保住按鈕本身的寬度，
   欄位不夠寬時讓表格自己橫向捲動，而不是把字擠爛 */
.action-cell .btn {
  white-space: nowrap;
  flex-shrink: 0;
}

.view-order-link {
  font-size: 0.85rem;
  font-weight: 700;
  color: var(--accent-hover);
  white-space: nowrap;
}

.view-order-link:hover {
  text-decoration: underline;
}
</style>
