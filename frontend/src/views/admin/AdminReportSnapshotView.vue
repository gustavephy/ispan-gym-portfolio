<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
// 按需引入，避免整包 echarts 被打進 bundle（用法比照 AdminReportView.vue）
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import {
  getReports,
  getReportDetail,
  getReportVersions,
  downloadReportPdf,
  downloadReportCsv,
  generateReport,
  voidReport,
} from '@/api/adminReport.js'
import { notifyApiError } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'
import { paymentMethodLabel } from '@/utils/paymentLabels.js'
import {
  isoDate,
  daysAgo,
  formatDate,
  formatDateTime,
  currency,
  number,
  SERIES_1,
  MUTED,
} from '@/utils/reportFormat.js'

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, CanvasRenderer])

const REPORT_TYPE_LABELS = { DAILY: '日報', MONTHLY: '月報', CUSTOM: '自訂' }
const STATUS_LABELS = {
  PENDING: '待處理',
  PAID: '已付款',
  SHIPPED: '已出貨',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUNDED: '已退款',
}

const reports = ref([])
const selectedReportId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)

// 已作廢的快照預設不列（誤生成的不該混在正常清單裡）；勾選才連同作廢的一起列——
// 作廢的現行版是該區間歷史版本的唯一入口，隱藏不等於消失
const includeVoided = ref(false)
watch(includeVoided, () => fetchReports())

// append-only 版本化：主下拉只列現行版；選中後抓同區間全部版本，
// 有多版時出現版本選單，可回看「當初生成的那份凍結數字」（可下載；不可刪除，誤生成只能作廢）
const versions = ref([])
const viewingVersionId = ref(null)
const viewingHistorical = computed(
  () => viewingVersionId.value != null && viewingVersionId.value !== selectedReportId.value,
)

async function fetchVersions(reportId) {
  if (!reportId) {
    versions.value = []
    return
  }
  try {
    const { data } = await getReportVersions(reportId)
    versions.value = data
  } catch {
    versions.value = [] // 版本清單抓不到只影響回看功能，不擋主流程
  }
}

const dailyTotals = computed(() => detail.value?.dailyTotals ?? [])

/**
 * 舊快照（2026-08-17 延伸統計擴充前生成）沒有算過每日營收，純量欄位全是 null，
 * 以 refundCount 判斷。只有這種才是「沒有資料」；新快照就算清單是空的，也是真的 0。
 */
const hasDailyStats = computed(() => detail.value != null && detail.value.refundCount != null)

// 品項型別對照：後端 ReportItemType
const TYPE_LABELS = { PRODUCT: '商品', COURSE: '課程', SUBSCRIPTION: '會籍' }

/**
 * 快照總覽：所選版本凍結當下的總計，切版本時跟著換，兩個版本的差異（訂單 0→1、退款 0→1 筆）一眼可比。
 * 退款與新會員是 2026-08-17 擴充的延伸統計，舊快照這幾格顯示「—」而不是 0，避免把「沒算過」讀成「沒有」。
 */
const snapshotKpis = computed(() => {
  const d = detail.value
  if (!d) return []
  const ext = d.refundCount != null
  const noStat = '此快照生成時尚無此統計'
  return [
    { key: 'orders', label: '訂單數', value: number.format(Number(d.orderCount ?? 0)) },
    { key: 'gross', label: '營收總額', value: currency.format(Number(d.totalAmount ?? 0)) },
    { key: 'discount', label: '折扣總額', value: currency.format(Number(d.discountAmount ?? 0)) },
    {
      key: 'refund',
      label: '退款金額',
      value: ext ? currency.format(Number(d.refundAmount ?? 0)) : '—',
      hint: ext ? `${number.format(Number(d.refundCount ?? 0))} 筆` : noStat,
      tone: ext && Number(d.refundAmount ?? 0) > 0 ? 'warn' : null,
    },
    {
      key: 'members',
      label: '新會員',
      value: ext ? number.format(Number(d.newMemberCount ?? 0)) : '—',
      hint: ext ? `新訂閱 ${number.format(Number(d.newSubscriptionCount ?? 0))}` : noStat,
    },
    { key: 'kinds', label: '有銷售的品項', value: number.format(d.items?.length ?? 0) },
  ]
})
/** 快照凍結的品項排行，與「排行 CSV」的內容相同 */
const snapshotItems = computed(() => detail.value?.items ?? [])

/**
 * 快照的每日營收補齊區間內每一天。後端 dailyRevenueBuckets 只回有付款訂單的日期：
 * 直接畫會把沒單的日子吃掉、兩個有單的日子被連成一線；日報或整段沒訂單時清單是空的，
 * 該畫零線而不是說「沒有資料」。區間最長 366 天（後端 validatePeriod），這裡保險上限 400。
 */
const trendRows = computed(() => {
  const d = detail.value
  if (!d?.periodStart || !d?.periodEnd) return []
  const byDate = new Map(
    dailyTotals.value.map((r) => [String(r.statDate).slice(0, 10), Number(r.revenue ?? 0)]),
  )
  const rows = []
  const cursor = new Date(`${d.periodStart}T00:00:00`)
  // 軸的終點取「區間迄日」與「生成日」較早者：月初就生成的月報，生成日之後的日子還沒發生，
  // 畫成 0 會被誤讀成下半月沒營收
  let end = new Date(`${d.periodEnd}T00:00:00`)
  if (d.generatedAt) {
    const generatedDay = new Date(`${String(d.generatedAt).slice(0, 10)}T00:00:00`)
    if (generatedDay < end) end = generatedDay
  }
  while (cursor <= end && rows.length < 400) {
    const key = isoDate(cursor)
    rows.push({ statDate: key, revenue: byDate.get(key) ?? 0 })
    cursor.setDate(cursor.getDate() + 1)
  }
  return rows
})
const trendAllZero = computed(
  () => trendRows.value.length > 0 && trendRows.value.every((r) => r.revenue === 0),
)
const statusBreakdown = computed(() => detail.value?.statusBreakdown ?? [])
const paymentBreakdown = computed(() => detail.value?.paymentBreakdown ?? [])

// ── 圖表 ────────────────────────────────────────────────────
const trendEl = ref(null)
let trendChart = null

function renderTrend() {
  if (!trendEl.value) return
  // 載入中時容器會被 v-else 的 spinner 換掉再重新掛載，舊 instance 綁的是已經
  // 脫離文件的節點，畫上去看不到。發現節點換人就先 dispose 再重建。
  if (trendChart && trendChart.getDom() !== trendEl.value) {
    trendChart.dispose()
    trendChart = null
  }
  if (!trendChart) trendChart = echarts.init(trendEl.value)
  const rows = trendRows.value
  // 日報或三天內的區間只有一兩個點，畫不出線；改長條比孤零零一個點好讀
  const asBar = rows.length <= 3
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
        // 全 0 時 ECharts 會自動把軸拉成 0～1 並切出 0.2、0.4 的小數刻度，金額軸不該有小數
        minInterval: 1,
        axisLabel: { color: MUTED, formatter: (v) => number.format(v) },
        splitLine: { lineStyle: { color: 'rgba(0,0,0,0.06)' } },
      },
      series: [
        {
          name: '營收',
          type: asBar ? 'bar' : 'line',
          data: rows.map((r) => Number(r.revenue ?? 0)),
          smooth: false,
          lineStyle: { width: 2 },
          symbolSize: 8,
          barMaxWidth: 48,
          // 長條模式（三天以內）直接把金額標在長條上，0 也會在基準線上顯示 $0，不會看起來像沒畫
          label: {
            show: asBar,
            position: 'top',
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
}

// ── 資料載入 ────────────────────────────────────────────────
async function fetchReports() {
  try {
    const { data } = await getReports({ includeVoided: includeVoided.value })
    reports.value = data
    // 預設選最新一份；明細與版本清單由 selectedReportId 的 watch 載入。
    // 重生成時 handleGenerate 已先切到新 id（此處值相同不再觸發），那次 watch 已載過
    if (data.length > 0) {
      selectedReportId.value = data[0].id
    } else {
      // 清單空了（全部作廢且未勾「顯示已作廢」）要跟著清掉，否則畫面會停留在最後一份快照的殘留資料
      selectedReportId.value = null
      viewingVersionId.value = null
      versions.value = []
      detail.value = null
    }
  } catch (e) {
    notifyApiError(e)
  }
}

// ── 手動生成報表快照 ────────────────────────────────────────
// 日報靠後端排程每天自動生成；月報／自訂區間目前完全沒有生成入口，
const showGenerateForm = ref(false)
const generating = ref(false)
const generateError = ref(null)
const genReportType = ref('DAILY')
const genDate = ref(isoDate(new Date()))
const genMonth = ref(
  `${new Date().getFullYear()}-${String(new Date().getMonth() + 1).padStart(2, '0')}`,
)
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
    const { data: created } = await generateReport({
      reportType: genReportType.value,
      periodStart,
      periodEnd,
    })
    // 同區間重複生成後舊版被標 superseded、退出主清單——回應一到就先把選取切到新 id，
    // 不等清單重抓；否則這段空窗內按「下載 PDF」／「作廢」會打到已被取代的舊版。
    if (created?.id != null) {
      selectedReportId.value = created.id
    }
    await fetchReports() // 清單重抓後最新一份（就是剛生成的這份）會被自動選取並載入明細
    showGenerateForm.value = false
  } catch (e) {
    generateError.value = e.message
  } finally {
    generating.value = false
  }
}

// ── 作廢正在檢視的報表快照 ──────────────────────────────────
// 快照不提供刪除：報表是稽核紀錄，誤生成（選錯區間、測試多按）只能作廢。
// 現行版與歷史版都可作廢；作廢後資料保留、清單預設隱藏，作廢人與原因記在快照上。
const voiding = ref(false)

async function handleVoid() {
  // 以「正在檢視的版本」為準（同 currentReportId）：回看歷史版時作廢的就是那一版。
  // 用 id 從清單找目標，不用 detail：生成後選取會立刻切到新 id，detail 要等重抓才更新
  const targetId = currentReportId()
  const target =
    versions.value.find((v) => v.id === targetId) ??
    reports.value.find((r) => r.id === targetId) ??
    detail.value
  if (!targetId || !target) return
  const reason = window.prompt(
    `確定要作廢這份報表快照嗎？（${REPORT_TYPE_LABELS[target.reportType] ?? target.reportType}｜${formatDate(target.periodStart)} – ${formatDate(target.periodEnd)}）\n作廢後資料保留、清單預設隱藏，此動作無法復原。請輸入作廢原因（可留空）：`,
    '',
  )
  if (reason === null) return // 按了取消

  voiding.value = true
  try {
    await voidReport(targetId, reason.trim())
    await fetchReports()
    // 作廢的是歷史版、或勾了「顯示已作廢」時，選取不會變、watch 不會觸發，這裡自己重抓
    await fetchVersions(selectedReportId.value)
    await fetchDetail(viewingVersionId.value ?? selectedReportId.value)
  } catch (e) {
    notifyApiError(e)
  } finally {
    voiding.value = false
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
  if (hasDailyStats.value) renderTrend()
}

watch(selectedReportId, (id) => {
  // 切換快照＝回到該區間的現行版；同時抓版本清單決定要不要顯示版本選單
  viewingVersionId.value = id
  fetchDetail(id)
  fetchVersions(id)
})

// PDF 由後端 ReportPdfService 產生（內嵌中文字型），這裡只負責把回傳的 blob
// 轉成下載連結；寫法比照 AiPlanPanel.vue 的 onDownloadPdf
const downloadingPdf = ref(false)

// 下載與作廢都以「正在檢視的版本」為準——回看歷史版本時下載／作廢的就是當初那份
const currentReportId = () => viewingVersionId.value ?? selectedReportId.value ?? detail.value?.id

async function onDownloadPdf() {
  await downloadBlob(
    () => downloadReportPdf(currentReportId()),
    'application/pdf',
    'pdf',
    (v) => (downloadingPdf.value = v),
  )
}

const downloadingCsv = ref(false)
async function onDownloadCsv() {
  await downloadBlob(
    () => downloadReportCsv(currentReportId()),
    'text/csv;charset=utf-8',
    'csv',
    (v) => (downloadingCsv.value = v),
  )
}

async function downloadBlob(fetcher, mime, ext, setBusy) {
  const report = detail.value
  if (!report) return
  setBusy(true)
  try {
    const { data } = await fetcher()
    const url = URL.createObjectURL(new Blob([data], { type: mime }))
    const a = document.createElement('a')
    a.href = url
    a.download = `operation-report-${report.id}${ext === 'csv' ? '-ranking' : ''}.${ext}`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  } catch (e) {
    notifyApiError(e)
  } finally {
    setBusy(false)
  }
}

onMounted(() => {
  fetchReports()
  window.addEventListener('resize', resizeCharts)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  trendChart?.dispose()
})
</script>

<template>
  <div class="page-container admin-snapshot-page">
    <span class="section-tag">ADMIN</span>
    <h1 class="section-title">報表快照</h1>
    <p class="section-desc">
      生成當下凍結的報表，供對帳與存證使用。重新生成不會覆蓋舊版——同區間的每一份都保留為
      歷史版本，可隨時回看當初的數字；誤生成只能作廢，不提供刪除。
    </p>

    <!-- 快照工具列：選快照、切版本、下載、作廢、生成。獨立一列而非塞在圖表 header，
         這一頁的所有內容都由這裡的選取決定 -->
    <section class="card snapshot-toolbar">
      <div class="report-picker">
        <label class="report-picker__field">
          <span class="filter__label">報表快照</span>
          <select v-model="selectedReportId" class="filter__input">
            <option v-for="r in reports" :key="r.id" :value="r.id">
              {{ REPORT_TYPE_LABELS[r.reportType] ?? r.reportType }}｜{{ formatDate(r.periodStart) }}
              – {{ formatDate(r.periodEnd) }}{{ r.voidedAt ? '｜已作廢' : '' }}
            </option>
          </select>
        </label>
        <!-- 快照不刪只作廢：作廢的預設不列，勾選才回來（帶「已作廢」標示） -->
        <label class="report-picker__field report-picker__check">
          <input v-model="includeVoided" type="checkbox" />
          <span class="filter__label">顯示已作廢</span>
        </label>
        <!-- append-only 版本化：可回看歷史版本。固定佔位、只有一版時 disabled，
             不用 v-if——出現／消失會讓右邊的按鈕左右橫跳 -->
        <label class="report-picker__field">
          <span class="filter__label">版本</span>
          <select
            v-model="viewingVersionId"
            class="filter__input report-picker__version"
            :disabled="versions.length <= 1"
            @change="fetchDetail(viewingVersionId)"
          >
            <option v-if="versions.length === 0" :value="viewingVersionId">現行版</option>
            <option v-for="v in versions" :key="v.id" :value="v.id">
              {{ v.supersededAt ? '歷史版' : '現行版' }}｜{{ formatDateTime(v.generatedAt) }} 生成{{
                v.voidedAt ? '｜已作廢' : ''
              }}
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
          class="btn"
          :disabled="!detail || downloadingCsv"
          @click="onDownloadCsv"
        >
          {{ downloadingCsv ? '產生中…' : '排行 CSV' }}
        </button>
        <!-- 作廢的是正在檢視的版本（歷史版也可）；已作廢的不能再作廢 -->
        <button
          type="button"
          class="btn btn-danger-outline"
          :disabled="!detail || voiding || !!detail?.voidedAt"
          @click="handleVoid"
        >
          {{ voiding ? '作廢中…' : detail?.voidedAt ? '已作廢' : '作廢' }}
        </button>
        <button type="button" class="btn btn-accent" @click="toggleGenerateForm">
          {{ showGenerateForm ? '取消' : '+ 產生報表' }}
        </button>
      </div>

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
          同類型、同區間若已有快照，這次的結果會成為現行版；舊的一份保留為歷史版本，
          可從「版本」選單回看（append-only，當初的數字永遠可追溯）。
        </p>
        <OperationMessage :message="generateError" />
      </div>
    </section>

    <p v-if="detail?.voidedAt" class="stale-notice stale-notice--void">
      此快照已於 {{ formatDateTime(detail.voidedAt) }} 作廢{{
        detail.voidReason ? `（原因：${detail.voidReason}）` : ''
      }}，數字僅供追溯、不得引用；需要有效數字請重新生成。
    </p>
    <p v-if="viewingHistorical" class="stale-notice">
      正在檢視歷史版本——這是當時生成的凍結數字（可下載存證；誤生成可作廢，紀錄仍保留）；
      現行數字請切回「現行版」。
    </p>
    <p v-if="detail?.staleAt && !viewingHistorical && !detail?.voidedAt" class="stale-notice">
      此報表生成後區間內發生過退款，數字已與現況不符，建議重新生成。
    </p>

    <!-- 快照總覽：所選版本凍結當下的總計；切版本時跟著換 -->
    <div v-if="detail" class="kpi-row">
      <div
        v-for="k in snapshotKpis"
        :key="k.key"
        class="card kpi"
        :class="{ 'kpi--warn': k.tone === 'warn' }"
      >
        <span class="kpi__label">{{ k.label }}</span>
        <span class="kpi__value">{{ k.value }}</span>
        <span v-if="k.hint" class="kpi__hint">{{ k.hint }}</span>
      </div>
    </div>

    <!-- 每日營收趨勢：所選快照凍結當下的數字 -->
    <section class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">每日營收趨勢</h2>
        <span v-if="detail" class="chart-card__sub">
          {{ formatDate(detail.periodStart) }} – {{ formatDate(detail.periodEnd) }}
        </span>
      </header>

      <div v-if="detailLoading" class="spinner"></div>
      <template v-else>
        <p v-if="reports.length === 0" class="empty-hint">
          目前沒有任何報表快照，點上方「+ 產生報表」建立第一份。
        </p>
        <p v-else-if="!hasDailyStats" class="empty-hint">
          這份報表沒有每日營收資料（2026-08-17 之前生成的快照沒有這項統計，重新生成即可補上）。
        </p>
        <div v-show="hasDailyStats" ref="trendEl" class="chart chart--trend"></div>
        <p v-if="hasDailyStats && trendAllZero" class="empty-hint">
          區間內沒有計入營收的付款訂單，每日營收為 0。
        </p>
      </template>
    </section>

    <!-- 訂單狀態分佈：分類少且要看確切數字，用表格比圖表清楚 -->
    <section v-if="statusBreakdown.length > 0" class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">訂單狀態分佈</h2>
        <span class="chart-card__sub">依所選快照</span>
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

    <!-- 付款方式分佈：快照凍結的訂單筆數，與營運統計頁的即時圓餅口徑不同 -->
    <section v-if="paymentBreakdown.length > 0" class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">付款方式分佈</h2>
        <span class="chart-card__sub">依所選快照</span>
      </header>
      <table class="data-table">
        <thead>
          <tr>
            <th>付款方式</th>
            <th class="col-num">訂單數</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in paymentBreakdown" :key="row.itemKey">
            <td>{{ paymentMethodLabel(row.itemKey) }}</td>
            <td class="col-num">{{ number.format(Number(row.orderCount ?? 0)) }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- 品項排行：所選快照凍結的排行明細，與「排行 CSV」內容相同 -->
    <section v-if="snapshotItems.length > 0" class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">品項排行</h2>
        <span class="chart-card__sub">依所選快照，與排行 CSV 相同</span>
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
          <tr v-for="row in snapshotItems" :key="`${row.productType}-${row.productId}-${row.rankNo}`">
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
.admin-snapshot-page {
  padding-bottom: 2rem;
}

.card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1rem 1.25rem;
  margin-bottom: 1rem;
}

/* 工具列：選單與按鈕同一列，窄螢幕才換行 */
.snapshot-toolbar {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.filter {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}

.filter__label {
  font-size: 0.78rem;
  color: var(--muted);
  white-space: nowrap;
}

.filter__input {
  padding: 0.45rem 0.6rem;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 0.9rem;
  background: #fff;
}

.report-picker {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 0.6rem;
}

.report-picker__field {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}

.report-picker__version {
  min-width: 15rem;
}

.report-picker__check {
  flex-direction: row;
  align-items: center;
  gap: 0.35rem;
  padding-bottom: 0.5rem;
}

.chart-card {
  padding: 1rem 1.25rem 1.25rem;
}

.chart-card__header {
  display: flex;
  align-items: baseline;
  gap: 0.6rem;
  margin-bottom: 0.75rem;
  flex-wrap: wrap;
}

.chart-card__title {
  font-size: 1rem;
  font-weight: 700;
  margin: 0;
}

.chart-card__sub {
  font-size: 0.8rem;
  color: var(--muted);
}

.chart {
  width: 100%;
}

.chart--trend {
  height: 320px;
}

/* .btn:disabled 一併定義：這一頁的下載／作廢按鈕都會用到 */
.btn-danger-outline {
  border: 1px solid #c75d76;
  color: #c75d76;
  background: transparent;
}

.btn-danger-outline:hover:not(:disabled) {
  background: rgba(199, 93, 118, 0.08);
}

.btn:disabled,
.btn-danger-outline:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.generate-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 0.75rem;
  padding-top: 0.75rem;
  border-top: 1px dashed var(--border);
}

.generate-hint {
  flex-basis: 100%;
  margin: 0;
  font-size: 0.78rem;
  color: var(--muted);
  line-height: 1.6;
}

/* ---- 快照總覽 KPI（樣式同營運統計頁的 .kpi） ---- */
.kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr));
  gap: 1rem;
  margin-bottom: 1rem;
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  padding: 1.25rem 1.5rem;
  margin-bottom: 0;
}

.kpi__label {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.kpi__value {
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

.col-num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.stale-notice {
  margin: 0 0 1rem;
  padding: 0.6rem 0.9rem;
  border-radius: 8px;
  background: rgba(232, 147, 61, 0.1);
  border: 1px solid rgba(232, 147, 61, 0.35);
  font-size: 0.85rem;
  line-height: 1.6;
}

.stale-notice--void {
  background: rgba(199, 93, 118, 0.1);
  border-color: rgba(199, 93, 118, 0.35);
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.88rem;
}

.data-table th,
.data-table td {
  padding: 0.5rem 0.6rem;
  border-bottom: 1px solid var(--border);
  text-align: left;
}

.data-table th {
  font-weight: 600;
  color: var(--muted);
  background: rgba(0, 0, 0, 0.02);
}

.data-table tbody tr:last-child td {
  border-bottom: none;
}

.col-num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
</style>
