<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
// 按需引入，避免整包 echarts（含地圖、3D 等用不到的模組）被打進 bundle
// （用法比照 InbodyPanel.vue）
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import {
  getLiveStats,
  getReports,
  getReportDetail,
  downloadReportPdf,
  generateReport,
  deleteReport,
} from '@/api/adminReport.js'
import { notifyApiError } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, CanvasRenderer])

// 品項型別對照：後端 ReportItemType
const TYPE_LABELS = {
  PRODUCT: '商品',
  COURSE: '課程',
  SUBSCRIPTION: '會籍',
}

const REPORT_TYPE_LABELS = { DAILY: '日報', MONTHLY: '月報', CUSTOM: '自訂區間' }

// 訂單狀態對照：後端 statusBreakdown 的 itemKey
const STATUS_LABELS = {
  PAID: '已付款',
  UNPAID: '未付款',
  REFUNDED: '已退款',
  CANCELLED: '已取消',
}

// ── 篩選條件 ────────────────────────────────────────────────
function isoDate(d) {
  // 不用 toISOString()：那會先轉 UTC，台灣時區晚上 8 點後會少算一天
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function daysAgo(n) {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d
}

const from = ref(isoDate(daysAgo(29)))
const to = ref(isoDate(new Date()))
const typeFilter = ref('')

const stats = ref(null)
const statsLoading = ref(true)
const statsError = ref(null)

// ── 歷史報表快照（趨勢圖與分佈的資料來源）──────────────────
const reports = ref([])
const selectedReportId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)

const currency = new Intl.NumberFormat('zh-TW', {
  style: 'currency',
  currency: 'TWD',
  maximumFractionDigits: 0,
})
const number = new Intl.NumberFormat('zh-TW')

function formatDate(value) {
  if (!value) return '—'
  return value.slice(0, 10).replace(/-/g, '/')
}

// ── KPI ─────────────────────────────────────────────────────
// 這幾個是「單一數字」，依 dataviz 的形式判斷用 stat tile 而不是圖表
const kpis = computed(() => {
  const s = stats.value
  if (!s) return []
  const tiles = [
    { key: 'gross', label: '區間營收', value: currency.format(Number(s.grossAmount ?? 0)) },
    { key: 'qty', label: '售出件數', value: number.format(Number(s.totalQuantity ?? 0)) },
    { key: 'kinds', label: '有銷售的品項', value: number.format(s.ranking?.length ?? 0) },
  ]
  const d = detail.value
  if (d) {
    tiles.push({
      key: 'refund',
      label: '退款金額',
      value: currency.format(Number(d.refundAmount ?? 0)),
      hint: `${number.format(Number(d.refundCount ?? 0))} 筆`,
      tone: Number(d.refundAmount ?? 0) > 0 ? 'warn' : null,
    })
  }
  return tiles
})

const hasRanking = computed(() => (stats.value?.ranking?.length ?? 0) > 0)

// 每列 44px＋上下留白；固定高度在只有兩三筆時長條會稀疏地散開，隨筆數成長才勻稱
const rankingHeight = computed(() => {
  const n = stats.value?.ranking?.length ?? 0
  return Math.max(120, n * 44 + 32)
})
const dailyTotals = computed(() => detail.value?.dailyTotals ?? [])
const statusBreakdown = computed(() => detail.value?.statusBreakdown ?? [])

// ── 圖表 ────────────────────────────────────────────────────
const trendEl = ref(null)
const rankingEl = ref(null)
let trendChart = null
let rankingChart = null

// 取自 dataviz 參考色盤（已用 validate_palette.js 驗證通過 light／dark 兩種模式）
const SERIES_1 = '#2a78d6'
const MUTED = '#9a8f88'
const INK = '#2b2320'

function renderTrend() {
  if (!trendEl.value) return
  // 載入中時容器會被 v-else 的 spinner 換掉再重新掛載，舊 instance 綁的是已經
  // 脫離文件的節點，畫上去看不到。發現節點換人就先 dispose 再重建。
  if (trendChart && trendChart.getDom() !== trendEl.value) {
    trendChart.dispose()
    trendChart = null
  }
  if (!trendChart) trendChart = echarts.init(trendEl.value)
  const rows = dailyTotals.value
  trendChart.setOption(
    {
      // 單一序列不需要圖例——標題已經說明它是什麼
      color: [SERIES_1],
      tooltip: {
        trigger: 'axis',
        valueFormatter: (v) => currency.format(Number(v ?? 0)),
      },
      grid: { left: 68, right: 24, top: 24, bottom: 32 },
      xAxis: {
        type: 'category',
        data: rows.map((r) => formatDate(r.statDate)),
        axisLine: { lineStyle: { color: MUTED } },
        axisLabel: { color: MUTED },
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: MUTED, formatter: (v) => number.format(v) },
        splitLine: { lineStyle: { color: 'rgba(0,0,0,0.06)' } },
      },
      series: [
        {
          name: '營收',
          type: 'line',
          data: rows.map((r) => Number(r.revenue ?? 0)),
          smooth: false,
          lineStyle: { width: 2 },
          symbolSize: 8,
        },
      ],
    },
    true,
  )
}

function renderRanking() {
  if (!rankingEl.value) return
  if (!rankingChart) rankingChart = echarts.init(rankingEl.value)
  // 由小到大排，橫向長條由下往上遞增，最暢銷的落在最上方
  const rows = [...(stats.value?.ranking ?? [])].reverse()
  rankingChart.setOption(
    {
      // 一個序列一個顏色——不依大小做深淺漸層，那會把長度重複編碼成色彩
      color: [SERIES_1],
      tooltip: {
        trigger: 'item',
        formatter: (p) => {
          const row = rows[p.dataIndex]
          return `${row.productName}<br/>${TYPE_LABELS[row.productType] ?? row.productType}｜售出 ${number.format(Number(row.quantitySold ?? 0))}<br/>${currency.format(Number(row.grossAmount ?? 0))}`
        },
      },
      grid: { left: 8, right: 88, top: 8, bottom: 8, containLabel: true },
      xAxis: { type: 'value', show: false },
      yAxis: {
        type: 'category',
        data: rows.map((r) => r.productName),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: INK },
      },
      series: [
        {
          type: 'bar',
          data: rows.map((r) => Number(r.grossAmount ?? 0)),
          barMaxWidth: 18,
          // 資料端 4px 圓角，貼齊基線那端保持方角
          itemStyle: { borderRadius: [0, 4, 4, 0] },
          // 直接標示數值，不必回頭對照座標軸（也滿足對比度較低時的可讀性要求）
          label: {
            show: true,
            position: 'right',
            color: MUTED,
            formatter: (p) => currency.format(Number(p.value ?? 0)),
          },
        },
      ],
    },
    true,
  )
}

function resizeCharts() {
  trendChart?.resize()
  rankingChart?.resize()
}

// ── 資料載入 ────────────────────────────────────────────────
async function fetchStats() {
  statsLoading.value = true
  statsError.value = null
  try {
    const { data } = await getLiveStats({
      from: from.value,
      to: to.value,
      type: typeFilter.value,
      top: 10,
    })
    stats.value = data
    // 等 nextTick 讓容器套用新的 rankingHeight 後再 resize，否則圖表仍用舊高度繪製
    await nextTick()
    if (hasRanking.value) {
      rankingChart?.resize()
      renderRanking()
    }
  } catch (e) {
    statsError.value = e.message
  } finally {
    statsLoading.value = false
  }
}

async function fetchReports() {
  try {
    const { data } = await getReports()
    reports.value = data
    // 預設選最新一份，讓趨勢圖一進來就有東西看
    if (data.length > 0) {
      selectedReportId.value = data[0].id
      await fetchDetail(data[0].id)
    } else {
      // 清單被刪光時要跟著清掉，否則畫面會停留在最後一份已刪除快照的殘留資料
      selectedReportId.value = null
      detail.value = null
    }
  } catch (e) {
    notifyApiError(e)
  }
}

// ── 手動生成報表快照 ────────────────────────────────────────
// 日報靠後端排程每天自動生成；月報／自訂區間目前完全沒有生成入口，
// 後端 generateReport 早就做好了，這裡補上前端表單讓它真的能被用到
const showGenerateForm = ref(false)
const generating = ref(false)
const generateError = ref(null)
const genReportType = ref('DAILY')
const genDate = ref(isoDate(new Date()))
const genMonth = ref(`${new Date().getFullYear()}-${String(new Date().getMonth() + 1).padStart(2, '0')}`)
const genFrom = ref(isoDate(daysAgo(29)))
const genTo = ref(isoDate(new Date()))

function toggleGenerateForm() {
  showGenerateForm.value = !showGenerateForm.value
  generateError.value = null
}

function lastDayOfMonth(yearMonth) {
  const [y, m] = yearMonth.split('-').map(Number)
  return isoDate(new Date(y, m, 0)) // 下個月第 0 天＝這個月最後一天
}

async function handleGenerate() {
  let periodStart
  let periodEnd
  if (genReportType.value === 'DAILY') {
    periodStart = periodEnd = genDate.value
  } else if (genReportType.value === 'MONTHLY') {
    periodStart = `${genMonth.value}-01`
    periodEnd = lastDayOfMonth(genMonth.value)
  } else {
    periodStart = genFrom.value
    periodEnd = genTo.value
  }

  generating.value = true
  generateError.value = null
  try {
    await generateReport({ reportType: genReportType.value, periodStart, periodEnd })
    await fetchReports() // 清單重抓後最新一份（就是剛生成的這份）會被自動選取
    showGenerateForm.value = false
  } catch (e) {
    generateError.value = e.message
  } finally {
    generating.value = false
  }
}

// ── 刪除目前選取的報表快照 ──────────────────────────────────
const deleting = ref(false)

async function handleDelete() {
  if (!detail.value) return
  const target = detail.value
  const confirmed = window.confirm(
    `確定要刪除這份報表快照嗎？（${REPORT_TYPE_LABELS[target.reportType] ?? target.reportType}｜${formatDate(target.periodStart)} – ${formatDate(target.periodEnd)}）此動作無法復原。`,
  )
  if (!confirmed) return

  deleting.value = true
  try {
    await deleteReport(target.id)
    await fetchReports()
  } catch (e) {
    notifyApiError(e)
  } finally {
    deleting.value = false
  }
}

async function fetchDetail(reportId) {
  if (!reportId) return
  detailLoading.value = true
  try {
    const { data } = await getReportDetail(reportId)
    detail.value = data
  } catch (e) {
    notifyApiError(e)
  } finally {
    detailLoading.value = false
  }
  // 必須等 detailLoading 轉回 false 之後才畫：載入中時圖表容器被 v-else 的 spinner
  // 換掉、根本不在 DOM 裡，trendEl 是 null，renderTrend() 會靜靜地什麼都不做，
  // 切換快照後畫面就一片空白。nextTick 等 v-else 分支重新掛載完成。
  await nextTick()
  if (dailyTotals.value.length > 0) renderTrend()
}

watch(selectedReportId, (id) => fetchDetail(id))

// PDF 由後端 ReportPdfService 產生（內嵌中文字型），這裡只負責把回傳的 blob
// 轉成下載連結；寫法比照 AiPlanPanel.vue 的 onDownloadPdf
const downloadingPdf = ref(false)

async function onDownloadPdf() {
  const report = detail.value
  if (!report) return
  downloadingPdf.value = true
  try {
    const { data } = await downloadReportPdf(report.id)
    const url = URL.createObjectURL(new Blob([data], { type: 'application/pdf' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `operation-report-${report.id}.pdf`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  } catch (e) {
    notifyApiError(e)
  } finally {
    downloadingPdf.value = false
  }
}

function applyFilter() {
  fetchStats()
}

onMounted(async () => {
  window.addEventListener('resize', resizeCharts)
  await Promise.all([fetchStats(), fetchReports()])
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  trendChart?.dispose()
  rankingChart?.dispose()
})
</script>

<template>
  <div class="page-container admin-report-page">
    <span class="section-tag">ADMIN</span>
    <h1 class="section-title">營運統計</h1>
    <p class="section-desc">即時銷售統計與歷史報表快照，用於掌握營收趨勢與熱門品項。</p>

    <!-- 篩選列：所有圖表共用同一組期間條件，統一放在最上方 -->
    <div class="filters card">
      <label class="filter">
        <span class="filter__label">起始日</span>
        <input v-model="from" type="date" class="filter__input" :max="to" />
      </label>
      <label class="filter">
        <span class="filter__label">結束日</span>
        <input v-model="to" type="date" class="filter__input" :min="from" />
      </label>
      <label class="filter">
        <span class="filter__label">品項型別</span>
        <select v-model="typeFilter" class="filter__input">
          <option value="">全部</option>
          <option value="PRODUCT">商品</option>
          <option value="COURSE">課程</option>
          <option value="SUBSCRIPTION">會籍</option>
        </select>
      </label>
      <button type="button" class="btn btn-accent filter__submit" @click="applyFilter">
        套用
      </button>
    </div>

    <OperationMessage :message="statsError" />

    <!-- KPI：單一數字用 stat tile，不做成只有一根長條的圖表 -->
    <div v-if="statsLoading" class="spinner"></div>
    <div v-else class="kpi-row">
      <div v-for="kpi in kpis" :key="kpi.key" class="kpi card" :class="{ 'kpi--warn': kpi.tone === 'warn' }">
        <span class="kpi__label">{{ kpi.label }}</span>
        <strong class="kpi__value">{{ kpi.value }}</strong>
        <span v-if="kpi.hint" class="kpi__hint">{{ kpi.hint }}</span>
      </div>
    </div>

    <!-- 熱門品項排行：橫向長條，品項名稱較長時比直向好讀 -->
    <section class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">熱門品項排行</h2>
        <span class="chart-card__sub">{{ formatDate(from) }} – {{ formatDate(to) }}．依營收排序</span>
      </header>
      <p v-if="!statsLoading && !hasRanking" class="empty-hint">此期間沒有銷售資料。</p>
      <!-- 高度隨品項數成長：固定高度在只有兩三筆時會讓長條稀疏地散在大片空白裡 -->
      <div
        v-show="hasRanking"
        ref="rankingEl"
        class="chart"
        :style="{ height: `${rankingHeight}px` }"
      ></div>
    </section>

    <!-- 歷史報表快照：趨勢與分佈的資料來源 -->
    <section class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">每日營收趨勢</h2>
        <div class="report-picker">
          <label class="report-picker__field">
            <span class="filter__label">報表快照</span>
            <select v-model="selectedReportId" class="filter__input">
              <option v-for="r in reports" :key="r.id" :value="r.id">
                {{ REPORT_TYPE_LABELS[r.reportType] ?? r.reportType }}｜{{ formatDate(r.periodStart) }} –
                {{ formatDate(r.periodEnd) }}
              </option>
            </select>
          </label>
          <!-- 下載的是所選快照的完整報表（含排行、分佈、每日營收），不是畫面上的圖 -->
          <button
            type="button"
            class="btn"
            :disabled="!detail || downloadingPdf"
            @click="onDownloadPdf"
          >
            {{ downloadingPdf ? '產生中…' : '下載 PDF' }}
          </button>
          <button
            type="button"
            class="btn btn-danger-outline"
            :disabled="!detail || deleting"
            @click="handleDelete"
          >
            {{ deleting ? '刪除中…' : '刪除' }}
          </button>
          <button type="button" class="btn btn-accent" @click="toggleGenerateForm">
            {{ showGenerateForm ? '取消' : '+ 產生報表' }}
          </button>
        </div>
      </header>

      <!-- 手動生成快照：日報靠排程自動生成，月報／自訂區間目前只能從這裡手動觸發 -->
      <div v-if="showGenerateForm" class="generate-form">
        <label class="filter">
          <span class="filter__label">報表類型</span>
          <select v-model="genReportType" class="filter__input">
            <option value="DAILY">日報（單日）</option>
            <option value="MONTHLY">月報（整月）</option>
            <option value="CUSTOM">自訂區間</option>
          </select>
        </label>

        <label v-if="genReportType === 'DAILY'" class="filter">
          <span class="filter__label">日期</span>
          <input v-model="genDate" type="date" class="filter__input" />
        </label>

        <label v-else-if="genReportType === 'MONTHLY'" class="filter">
          <span class="filter__label">月份</span>
          <input v-model="genMonth" type="month" class="filter__input" />
        </label>

        <template v-else>
          <label class="filter">
            <span class="filter__label">起始日</span>
            <input v-model="genFrom" type="date" class="filter__input" :max="genTo" />
          </label>
          <label class="filter">
            <span class="filter__label">結束日</span>
            <input v-model="genTo" type="date" class="filter__input" :min="genFrom" />
          </label>
        </template>

        <button type="button" class="btn btn-accent" :disabled="generating" @click="handleGenerate">
          {{ generating ? '生成中…' : '確定生成' }}
        </button>

        <p class="generate-hint">
          同類型、同區間若已有快照，會直接以這次的結果取代舊的一份。
        </p>
        <OperationMessage :message="generateError" />
      </div>

      <p v-if="detail?.staleAt" class="stale-notice">
        此報表生成後區間內發生過退款，數字已與現況不符，建議重新生成。
      </p>

      <div v-if="detailLoading" class="spinner"></div>
      <template v-else>
        <p v-if="reports.length === 0" class="empty-hint">
          目前沒有任何報表快照，點上方「+ 產生報表」建立第一份。
        </p>
        <p v-else-if="dailyTotals.length === 0" class="empty-hint">
          這份報表沒有每日營收資料（較早生成的快照沒有這項統計）。
        </p>
        <div v-show="dailyTotals.length > 0" ref="trendEl" class="chart chart--trend"></div>
      </template>
    </section>

    <!-- 訂單狀態分佈：分類少且要看確切數字，用表格比圖表清楚 -->
    <section v-if="statusBreakdown.length > 0" class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">訂單狀態分佈</h2>
        <span class="chart-card__sub">依所選報表快照</span>
      </header>
      <table class="data-table">
        <thead>
          <tr>
            <th>狀態</th>
            <th class="col-num">訂單數</th>
            <th class="col-num">金額</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in statusBreakdown" :key="row.itemKey">
            <td>{{ STATUS_LABELS[row.itemKey] ?? row.itemKey }}</td>
            <td class="col-num">{{ number.format(Number(row.orderCount ?? 0)) }}</td>
            <td class="col-num">
              {{ row.amount == null ? '—' : currency.format(Number(row.amount)) }}
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- 排行的表格版：圖表之外也提供可讀的數字，兼顧無障礙與精確比對 -->
    <section v-if="hasRanking" class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">品項明細</h2>
        <span class="chart-card__sub">熱門品項排行的表格版</span>
      </header>
      <table class="data-table">
        <thead>
          <tr>
            <th class="col-num">#</th>
            <th>品項</th>
            <th>型別</th>
            <th class="col-num">售出</th>
            <th class="col-num">營收</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in stats.ranking" :key="`${row.productType}-${row.productId}`">
            <td class="col-num">{{ row.rankNo }}</td>
            <td>{{ row.productName }}</td>
            <td>{{ TYPE_LABELS[row.productType] ?? row.productType }}</td>
            <td class="col-num">{{ number.format(Number(row.quantitySold ?? 0)) }}</td>
            <td class="col-num">{{ currency.format(Number(row.grossAmount ?? 0)) }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<style scoped>
.admin-report-page {
  padding-bottom: 4rem;
}

.card {
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

/* ---- 篩選列 ---- */
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 1.5rem;
  padding: 1.5rem 1.75rem;
  margin-bottom: 1.5rem;
}

.filter {
  display: flex;
  flex-direction: column;
  gap: 0.45rem;
}

.filter__label {
  font-size: 0.8rem;
  font-weight: 700;
  color: var(--text-secondary);
}

.filter__input {
  padding: 0.6rem 0.8rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.95rem;
  min-width: 9rem;
}

.filter__submit {
  padding: 0.6rem 1.5rem;
}

/* ---- KPI ---- */
.kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr));
  gap: 1rem;
  margin-bottom: 1.5rem;
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  padding: 1.25rem 1.5rem;
}

.kpi__label {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.kpi__value {
  /* 數字是這塊的主角，字級明顯拉大；等寬讓多張卡片的位數對齊 */
  font-size: 1.75rem;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  color: var(--text-primary);
}

.kpi__hint {
  font-size: 0.8rem;
  color: var(--text-muted);
}

/* 退款有值時給一個克制的提示色，不用整張卡變色 */
.kpi--warn .kpi__value {
  color: #9b2c1e;
}

/* ---- 圖表卡片 ---- */
.chart-card {
  padding: 1.5rem 1.75rem;
  margin-bottom: 1.5rem;
}

.chart-card__header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1.25rem;
}

.chart-card__title {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 800;
}

.chart-card__sub {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.report-picker {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-left: auto;
}

.report-picker__field {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}

.chart {
  width: 100%;
}

.chart--trend {
  height: 300px;
}


.btn-danger-outline {
  border: 1.5px solid #c92a2a;
  color: #c92a2a;
  background: transparent;
}
.btn-danger-outline:hover:not(:disabled) {
  background: #c92a2a;
  color: #fff;
}
.btn-danger-outline:disabled,
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.generate-form {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 1.25rem;
  padding: 1.25rem 1.5rem;
  margin-bottom: 1.25rem;
  border: 1px dashed var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
}
.generate-hint {
  flex-basis: 100%;
  font-size: 0.78rem;
  color: var(--text-muted);
}

.stale-notice {
  margin: 0 0 1rem;
  padding: 0.6rem 0.9rem;
  background: #fbf1d9;
  color: #8a6410;
  border-radius: var(--radius-sm);
  font-size: 0.9rem;
}

/* ---- 表格 ---- */
.data-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 0.95rem;
}

.data-table th,
.data-table td {
  padding: 0.75rem 1rem;
  text-align: left;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

.data-table th:first-child,
.data-table td:first-child {
  padding-left: 0.75rem;
}

.data-table th {
  font-size: 0.85rem;
  color: var(--text-secondary);
  font-weight: 700;
  background: var(--bg-soft);
}

.data-table thead tr:first-child th:first-child {
  border-radius: var(--radius-sm) 0 0 var(--radius-sm);
}
.data-table thead tr:first-child th:last-child {
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
}

.data-table tbody tr:last-child td {
  border-bottom: none;
}

/* 數字右對齊並等寬，位數才對得齊、方便比大小 */
.col-num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
</style>
