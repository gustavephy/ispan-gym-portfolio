<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import {
  searchPaymentTransactions,
  searchPaymentRefunds,
  searchPaymentWebhooks,
} from '@/api/adminPayment.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'
import Pagination from '@/components/Pagination.vue'
import {
  paymentMethodLabel,
  PAYMENT_STATUS_LABELS,
  REFUND_STATUS_LABELS,
  WEBHOOK_STATUS_LABELS,
  PAYMENT_PROVIDER_LABELS,
} from '@/utils/paymentLabels.js'

const tabs = [
  { key: 'transactions', label: '付款交易' },
  { key: 'refunds', label: '退款紀錄' },
  { key: 'webhooks', label: '金流回呼（Webhook）' },
]
const activeTab = ref('transactions')

const rows = ref([])
const loading = ref(false)
const listMessage = ref('')
const page = ref(0)
const size = ref(20)
const totalPages = ref(0)
const expandedId = ref(null) // webhook 展開看 payload

const filters = reactive({
  status: '',
  provider: '',
  processingStatus: '',
  keyword: '',
  fromDate: '',
  toDate: '',
})

const searchFn = {
  transactions: searchPaymentTransactions,
  refunds: searchPaymentRefunds,
  webhooks: searchPaymentWebhooks,
}

function params() {
  const base = {
    keyword: filters.keyword || undefined,
    from: filters.fromDate ? `${filters.fromDate}T00:00:00` : undefined,
    to: filters.toDate ? `${filters.toDate}T23:59:59` : undefined,
    page: page.value,
    size: size.value,
  }
  if (activeTab.value === 'transactions') {
    return { ...base, status: filters.status || undefined, provider: filters.provider || undefined }
  }
  if (activeTab.value === 'refunds') {
    return { ...base, status: filters.status || undefined }
  }
  return { ...base, processingStatus: filters.processingStatus || undefined, provider: filters.provider || undefined }
}

async function fetchRows() {
  loading.value = true
  listMessage.value = ''
  try {
    const res = await searchFn[activeTab.value](params())
    rows.value = res.data.content
    totalPages.value = res.data.totalPages
  } catch (err) {
    if (isInlineError(err)) listMessage.value = err.message
    else notifyApiError(err)
  } finally {
    loading.value = false
  }
}

function resetAndFetch() {
  page.value = 0
  expandedId.value = null
  fetchRows()
}

function switchTab(key) {
  if (activeTab.value === key) return
  activeTab.value = key
  filters.status = ''
  filters.provider = ''
  filters.processingStatus = ''
  filters.keyword = ''
  filters.fromDate = ''
  filters.toDate = ''
  resetAndFetch()
}

watch(page, fetchRows)
watch(size, resetAndFetch)

function formatDate(iso) {
  return iso ? new Date(iso).toLocaleString('zh-TW', { hour12: false }) : '—'
}
function money(v) {
  return `NT$ ${Number(v).toLocaleString()}`
}

const statusOptions = computed(() => {
  if (activeTab.value === 'transactions') return PAYMENT_STATUS_LABELS
  if (activeTab.value === 'refunds') return REFUND_STATUS_LABELS
  return {}
})

onMounted(fetchRows)
</script>

<template>
  <div class="page-container">
    <span class="section-tag">ADMIN</span>
    <h2 class="section-title">交易紀錄</h2>
    <p class="section-desc">
      查看所有金流交易、退款與綠界回呼。排查卡住的付款時，用 Webhook 分頁篩「處理失敗」看綠界實際送了什麼。
    </p>

    <div class="pay-tabs">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="pay-tab"
        :class="{ active: activeTab === t.key }"
        @click="switchTab(t.key)"
      >{{ t.label }}</button>
    </div>

    <div class="filter-bar">
      <div v-if="activeTab !== 'webhooks'" class="filter-field">
        <label>狀態</label>
        <select v-model="filters.status">
          <option value="">全部</option>
          <option v-for="(label, key) in statusOptions" :key="key" :value="key">{{ label }}</option>
        </select>
      </div>
      <div v-if="activeTab === 'webhooks'" class="filter-field">
        <label>處理狀態</label>
        <select v-model="filters.processingStatus">
          <option value="">全部</option>
          <option v-for="(label, key) in WEBHOOK_STATUS_LABELS" :key="key" :value="key">{{ label }}</option>
        </select>
      </div>
      <div v-if="activeTab !== 'refunds'" class="filter-field">
        <label>金流商</label>
        <select v-model="filters.provider">
          <option value="">全部</option>
          <option value="ECPAY">綠界 ECPay</option>
          <option value="SIMULATE">模擬付款</option>
        </select>
      </div>
      <div class="filter-field">
        <label>關鍵字</label>
        <input v-model="filters.keyword" placeholder="訂單 / 交易編號" @keyup.enter="resetAndFetch" />
      </div>
      <div class="filter-field">
        <label>日期（起）</label>
        <input v-model="filters.fromDate" type="date" />
      </div>
      <div class="filter-field">
        <label>日期（迄）</label>
        <input v-model="filters.toDate" type="date" />
      </div>
      <button class="btn btn-accent" type="button" @click="resetAndFetch">搜尋</button>
    </div>

    <OperationMessage :message="listMessage" />
    <div v-if="loading" class="spinner"></div>

    <template v-else>
      <p v-if="rows.length === 0" class="empty-state">查無資料。</p>

      <!-- 付款交易 -->
      <table v-else-if="activeTab === 'transactions'" class="slot-table">
        <thead>
          <tr>
            <th>訂單</th><th>特店交易編號</th><th>金流商</th><th>方式</th><th>金額</th>
            <th>狀態</th><th>綠界回覆</th><th>付款時間</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.id">
            <td class="mono">{{ r.orderNo }}</td>
            <td class="mono">{{ r.merchantTradeNo }}</td>
            <td>{{ PAYMENT_PROVIDER_LABELS[r.provider] ?? r.provider }}</td>
            <td>{{ paymentMethodLabel(r.paymentMethod) }}<span v-if="r.simulatePaid" class="tag">模擬</span></td>
            <td>{{ money(r.amount) }}</td>
            <td><span class="status-chip" :class="r.status">{{ PAYMENT_STATUS_LABELS[r.status] ?? r.status }}</span></td>
            <td class="muted">{{ r.rtnCode != null ? `${r.rtnCode} ${r.rtnMsg ?? ''}` : '—' }}</td>
            <td>{{ formatDate(r.paidAt) }}</td>
          </tr>
        </tbody>
      </table>

      <!-- 退款紀錄 -->
      <table v-else-if="activeTab === 'refunds'" class="slot-table">
        <thead>
          <tr>
            <th>訂單</th><th>退款單號</th><th>金額</th><th>狀態</th><th>申請原因</th><th>申請時間</th><th>完成時間</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.refundId">
            <td class="mono">{{ r.merchantTradeNo }}</td>
            <td class="mono">{{ r.refundNo }}</td>
            <td>{{ money(r.amount) }}</td>
            <td><span class="status-chip" :class="r.status">{{ REFUND_STATUS_LABELS[r.status] ?? r.status }}</span></td>
            <td class="reason">{{ r.reason || '未填寫' }}</td>
            <td>{{ formatDate(r.requestedAt) }}</td>
            <td>{{ formatDate(r.completedAt) }}</td>
          </tr>
        </tbody>
      </table>

      <!-- Webhook -->
      <table v-else class="slot-table">
        <thead>
          <tr>
            <th>金流商</th><th>event key</th><th>特店交易編號</th><th>驗簽</th><th>處理狀態</th><th>接收時間</th><th></th>
          </tr>
        </thead>
        <tbody>
          <template v-for="r in rows" :key="r.id">
            <tr :class="{ 'row-error': r.processingStatus === 'ERROR' }">
              <td>{{ PAYMENT_PROVIDER_LABELS[r.provider] ?? r.provider }}</td>
              <td class="mono">{{ r.eventKey }}</td>
              <td class="mono">{{ r.merchantTradeNo }}</td>
              <td>{{ r.checkMacValid ? '✓' : '✗' }}</td>
              <td><span class="status-chip" :class="r.processingStatus">{{ WEBHOOK_STATUS_LABELS[r.processingStatus] ?? r.processingStatus }}</span></td>
              <td>{{ formatDate(r.receivedAt) }}</td>
              <td>
                <button class="link-btn" type="button" @click="expandedId = expandedId === r.id ? null : r.id">
                  {{ expandedId === r.id ? '收合' : '詳情' }}
                </button>
              </td>
            </tr>
            <tr v-if="expandedId === r.id" class="detail-row">
              <td colspan="7">
                <p v-if="r.errorMessage" class="err-msg">錯誤：{{ r.errorMessage }}</p>
                <pre class="payload">{{ r.payloadPreview }}</pre>
              </td>
            </tr>
          </template>
        </tbody>
      </table>

      <Pagination
        :page="page"
        :total-pages="totalPages"
        :size="size"
        :size-options="[10, 20, 50]"
        @update:page="page = $event"
        @update:size="size = $event"
      />
    </template>
  </div>
</template>

<style scoped>
.pay-tabs {
  display: flex;
  gap: 0.5rem;
  margin: 1.25rem 0 0;
  border-bottom: 1px solid var(--border);
}
.pay-tab {
  padding: 0.6rem 1rem;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-muted);
  cursor: pointer;
}
.pay-tab.active {
  color: var(--accent-hover);
  border-bottom-color: var(--accent-hover);
}
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 1rem;
  margin: 1.5rem 0;
  padding: 1.1rem 1.25rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-section);
}
.filter-field {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  font-size: 0.82rem;
  color: var(--text-secondary);
}
.filter-field input,
.filter-field select {
  padding: 0.5rem 0.7rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font: inherit;
  color: var(--text-primary);
  min-width: 150px;
}
.empty-state { color: var(--text-muted); padding: 2rem 0; }
.slot-table {
  width: 100%;
  border-collapse: collapse;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
  font-size: 0.85rem;
}
.slot-table th,
.slot-table td {
  text-align: left;
  padding: 0.7rem 0.9rem;
  border-bottom: 1px solid var(--border);
}
.slot-table th {
  color: var(--text-secondary);
  font-size: 0.8rem;
  font-weight: 700;
  background: var(--bg-soft);
}
.slot-table tbody tr:last-child td { border-bottom: none; }
.mono {
  font-family: ui-monospace, "SF Mono", Consolas, monospace;
  font-size: 0.8rem;
}
.muted { color: var(--text-muted); }
.reason { max-width: 320px; }
.status-chip {
  padding: 0.1rem 0.5rem;
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 700;
  background: var(--bg-soft);
  color: var(--text-secondary);
}
.status-chip.SUCCESS, .status-chip.COMPLETED, .status-chip.PROCESSED { background: #d4f4dd; color: #157347; }
.status-chip.FAILED, .status-chip.ERROR, .status-chip.REJECTED { background: #fde2e2; color: #c92a2a; }
.status-chip.PENDING, .status-chip.RECEIVED { background: #fff3cd; color: #a06400; }
.row-error td { background: #fdf1f0; }
.tag {
  margin-left: 0.35rem;
  padding: 0.02rem 0.35rem;
  border-radius: 4px;
  background: #e7edfc;
  color: #1d4ed8;
  font-size: 0.68rem;
  font-weight: 700;
}
.link-btn {
  background: none;
  border: none;
  color: var(--accent-hover);
  font-size: 0.82rem;
  font-weight: 600;
  cursor: pointer;
}
.detail-row td { background: var(--bg-soft); }
.err-msg { color: #c92a2a; font-size: 0.82rem; margin: 0 0 0.5rem; }
.payload {
  margin: 0;
  padding: 0.6rem 0.8rem;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.75rem;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 240px;
  overflow: auto;
}
</style>
