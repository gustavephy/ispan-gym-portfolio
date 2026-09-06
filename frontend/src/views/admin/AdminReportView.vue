<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
// 按需引入，避免整包 echarts（含地圖、3D 等用不到的模組）被打進 bundle
// （用法比照 InbodyPanel.vue）
import * as echarts from 'echarts/core'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import {
  getLiveStats,
  getOrderOverview,
  getGroupClassFill,
  getMemberGrowth,
  getTrainerUtilization,
  getCouponEffect,
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

echarts.use([LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

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

// 期間快選：對齊「營運分析」Metabase 儀表板的日期選擇器，選了就立刻套用；
// 手動改起迄日則自動切回「自訂區間」，再按套用
const RANGE_PRESETS = [
  { value: 'today', label: '今天' },
  { value: 'yesterday', label: '昨天' },
  { value: 'last7', label: '近 7 天' },
  { value: 'last30', label: '近 30 天' },
  { value: 'thisMonth', label: '本月' },
  { value: 'lastMonth', label: '上月' },
  { value: 'custom', label: '自訂區間' },
]
const rangePreset = ref('last30')

/** 快選對應的 [起日, 迄日]（含頭含尾，與後端口徑一致）；自訂區間回 null */
function presetRange(preset) {
  const today = new Date()
  switch (preset) {
    case 'today':
      return [isoDate(today), isoDate(today)]
    case 'yesterday':
      return [isoDate(daysAgo(1)), isoDate(daysAgo(1))]
    case 'last7':
      return [isoDate(daysAgo(6)), isoDate(today)]
    case 'last30':
      return [isoDate(daysAgo(29)), isoDate(today)]
    case 'thisMonth':
      return [isoDate(new Date(today.getFullYear(), today.getMonth(), 1)), isoDate(today)]
    case 'lastMonth':
      // 下個月第 0 天＝上個月最後一天
      return [
        isoDate(new Date(today.getFullYear(), today.getMonth() - 1, 1)),
        isoDate(new Date(today.getFullYear(), today.getMonth(), 0)),
      ]
    default:
      return null
  }
}

function onPresetChange() {
  const range = presetRange(rangePreset.value)
  if (!range) return // 自訂區間：保留目前日期，讓使用者自己改再按套用
  from.value = range[0]
  to.value = range[1]
  fetchStats()
}

// 手動改日期就切成自訂區間；由快選寫入的日期和快選一致，不會誤切
watch([from, to], ([f, t]) => {
  const range = presetRange(rangePreset.value)
  if (range && (range[0] !== f || range[1] !== t)) rangePreset.value = 'custom'
})

const stats = ref(null)
const statsLoading = ref(true)
const statsError = ref(null)
// 即時營運總覽：訂單量/退款/新會員與付款方式分布（口徑：下單日 createdAt，與 /stats 的付款日不同）
const overview = ref(null)
// 團課滿座率與每日新會員：同樣跟期間篩選連動的即時統計
const classFill = ref([])
const memberDaily = ref([])
// 教練時段使用率（跟期間連動）；優惠券成效看整個生命週期、不吃期間
const trainerUtil = ref([])
const couponEffect = ref([])

// ── 歷史報表快照（趨勢圖與分佈的資料來源）──────────────────
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

function formatDateTime(value) {
  return String(value ?? '').replace('T', ' ').slice(0, 16)
}

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
  // 後三張改吃即時 overview（原本退款吃快照 detail，沒選快照就看不到，且期間對不上篩選列）
  const o = overview.value
  if (o) {
    // 客單價分母只算已收款訂單（PAID/SHIPPED/COMPLETED），與營收口徑一致
    const paidOrders = ['PAID', 'SHIPPED', 'COMPLETED']
      .reduce((sum, k) => sum + Number(o.countByStatus?.[k] ?? 0), 0)
    tiles.push({
      key: 'orders',
      label: '訂單數',
      value: number.format(Number(o.totalOrders ?? 0)),
      hint: paidOrders > 0 ? `客單價 ${currency.format(Number(o.totalRevenue ?? 0) / paidOrders)}` : null,
    })
    tiles.push({
      key: 'refund',
      label: '退款金額',
      value: currency.format(Number(o.refundAmount ?? 0)),
      hint: `${number.format(Number(o.refundCount ?? 0))} 筆`,
      tone: Number(o.refundAmount ?? 0) > 0 ? 'warn' : null,
    })
    tiles.push({
      key: 'members',
      label: '新會員',
      value: number.format(Number(o.newMemberCount ?? 0)),
      hint: `新訂閱 ${number.format(Number(o.newSubscriptionCount ?? 0))}`,
    })
  }
  return tiles
})

const hasPaymentData = computed(
  () => Object.keys(overview.value?.countByPaymentMethod ?? {}).length > 0,
)
const hasClassFill = computed(() => classFill.value.length > 0)
const hasMemberDaily = computed(() => memberDaily.value.length > 0)
const hasTrainerUtil = computed(() => trainerUtil.value.length > 0)
// 與排行同律：高度隨課程數成長
const classFillHeight = computed(() => Math.max(120, classFill.value.length * 44 + 32))
const trainerUtilHeight = computed(() => Math.max(120, trainerUtil.value.length * 44 + 32))

// 優惠券成效表格列：比率先算好，模板只負責顯示
const couponRows = computed(() =>
  couponEffect.value.map((c) => {
    const total = Number(c.totalQuantity ?? 0)
    const claimed = Number(c.claimedQuantity ?? 0)
    const used = Number(c.usedCount ?? 0)
    return {
      ...c,
      claimRate: total > 0 ? ((claimed / total) * 100).toFixed(1) : '—',
      useRate: claimed > 0 ? ((used / claimed) * 100).toFixed(1) : '—',
    }
  }),
)

const hasRanking = computed(() => (stats.value?.ranking?.length ?? 0) > 0)

// 每列 44px＋上下留白；固定高度在只有兩三筆時長條會稀疏地散開，隨筆數成長才勻稱
const rankingHeight = computed(() => {
  const n = stats.value?.ranking?.length ?? 0
  return Math.max(120, n * 44 + 32)
})
const dailyTotals = computed(() => detail.value?.dailyTotals ?? [])
const statusBreakdown = computed(() => detail.value?.statusBreakdown ?? [])
const paymentBreakdown = computed(() => detail.value?.paymentBreakdown ?? [])

// ── 圖表 ────────────────────────────────────────────────────
const trendEl = ref(null)
const rankingEl = ref(null)
const paymentEl = ref(null)
const classFillEl = ref(null)
const memberGrowthEl = ref(null)
const trainerUtilEl = ref(null)
let trendChart = null
let rankingChart = null
let paymentChart = null
let classFillChart = null
let memberGrowthChart = null
let trainerUtilChart = null

// 取自 dataviz 參考色盤（已用 validate_palette.js 驗證通過 light／dark 兩種模式）
const SERIES_1 = '#2a78d6'
const MUTED = '#9a8f88'
const INK = '#2b2320'
// 付款方式圓餅的類別色：首色沿用 SERIES_1，其餘取同明度的對比色相
const CATEGORICAL = [SERIES_1, '#e8933d', '#43a06d', '#9268c9', '#c75d76']

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

function renderPaymentPie() {
  if (!paymentEl.value) return
  // 同 renderTrend 的節點換人防護：v-show 雖不卸載節點，保險起見比照處理
  if (paymentChart && paymentChart.getDom() !== paymentEl.value) {
    paymentChart.dispose()
    paymentChart = null
  }
  if (!paymentChart) paymentChart = echarts.init(paymentEl.value)
  const entries = Object.entries(overview.value?.countByPaymentMethod ?? {})
  paymentChart.setOption(
    {
      color: CATEGORICAL,
      tooltip: {
        trigger: 'item',
        formatter: (p) => `${p.name}：${number.format(p.value)} 筆（${p.percent}%）`,
      },
      legend: { bottom: 0, textStyle: { color: INK } },
      series: [
        {
          type: 'pie',
          // 甜甜圈：中空讓佔比讀起來不那麼像面積比較，圓心留白也讓整體不擁擠
          radius: ['42%', '68%'],
          center: ['50%', '44%'],
          data: entries.map(([method, count]) => ({
            name: paymentMethodLabel(method),
            value: Number(count ?? 0),
          })),
          label: { color: INK, formatter: '{b} {d}%' },
        },
      ],
    },
    true,
  )
}

function renderClassFill() {
  if (!classFillEl.value) return
  if (!classFillChart) classFillChart = echarts.init(classFillEl.value)
  // 滿座率由低到高排，橫向長條最滿的落在最上方（同排行圖的方向邏輯）
  const rows = [...classFill.value]
    .map((r) => ({ ...r, pct: r.capacityTotal > 0 ? (r.bookedTotal / r.capacityTotal) * 100 : 0 }))
    .sort((a, b) => a.pct - b.pct)
  classFillChart.setOption(
    {
      color: [SERIES_1],
      tooltip: {
        trigger: 'item',
        formatter: (p) => {
          const row = rows[p.dataIndex]
          return `${row.courseName}<br/>${number.format(row.sessionCount)} 場次｜報名 ${number.format(row.bookedTotal)}/${number.format(row.capacityTotal)} 人次<br/>滿座率 ${row.pct.toFixed(1)}%`
        },
      },
      grid: { left: 8, right: 96, top: 8, bottom: 8, containLabel: true },
      // 滿座率是 0–100 的比例，軸固定到 100 讓「離滿座多遠」讀得出來
      xAxis: { type: 'value', max: 100, show: false },
      yAxis: {
        type: 'category',
        data: rows.map((r) => r.courseName),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: INK },
      },
      series: [
        {
          type: 'bar',
          data: rows.map((r) => Number(r.pct.toFixed(1))),
          barMaxWidth: 18,
          itemStyle: { borderRadius: [0, 4, 4, 0] },
          label: {
            show: true,
            position: 'right',
            color: MUTED,
            formatter: (p) => {
              const row = rows[p.dataIndex]
              return `${number.format(row.bookedTotal)}/${number.format(row.capacityTotal)}（${row.pct.toFixed(0)}%）`
            },
          },
        },
      ],
    },
    true,
  )
}

function renderMemberGrowth() {
  if (!memberGrowthEl.value) return
  if (!memberGrowthChart) memberGrowthChart = echarts.init(memberGrowthEl.value)
  const rows = memberDaily.value
  memberGrowthChart.setOption(
    {
      color: [SERIES_1],
      tooltip: {
        trigger: 'axis',
        valueFormatter: (v) => `${number.format(Number(v ?? 0))} 人`,
      },
      grid: { left: 48, right: 24, top: 24, bottom: 32 },
      xAxis: {
        type: 'category',
        data: rows.map((r) => formatDate(r.statDate)),
        axisLine: { lineStyle: { color: MUTED } },
        axisLabel: { color: MUTED },
      },
      yAxis: {
        type: 'value',
        minInterval: 1, // 人數是整數，不出現 0.5 這種刻度
        axisLabel: { color: MUTED, formatter: (v) => number.format(v) },
        splitLine: { lineStyle: { color: 'rgba(0,0,0,0.06)' } },
      },
      series: [
        {
          name: '新會員',
          type: 'line',
          data: rows.map((r) => Number(r.newMembers ?? 0)),
          smooth: false,
          lineStyle: { width: 2 },
          symbolSize: 8,
        },
      ],
    },
    true,
  )
}

function renderTrainerUtil() {
  if (!trainerUtilEl.value) return
  if (!trainerUtilChart) trainerUtilChart = echarts.init(trainerUtilEl.value)
  // 使用率由低到高排，最搶手的教練落在最上方（同滿座率圖）
  const rows = [...trainerUtil.value]
    .map((r) => ({ ...r, pct: r.totalSlots > 0 ? (r.bookedSlots / r.totalSlots) * 100 : 0 }))
    .sort((a, b) => a.pct - b.pct)
  trainerUtilChart.setOption(
    {
      color: [SERIES_1],
      tooltip: {
        trigger: 'item',
        formatter: (p) => {
          const row = rows[p.dataIndex]
          return `${row.trainerName}<br/>開放 ${number.format(row.totalSlots)} 時段｜已預約 ${number.format(row.bookedSlots)}<br/>使用率 ${row.pct.toFixed(1)}%`
        },
      },
      grid: { left: 8, right: 96, top: 8, bottom: 8, containLabel: true },
      xAxis: { type: 'value', max: 100, show: false },
      yAxis: {
        type: 'category',
        data: rows.map((r) => r.trainerName),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: INK },
      },
      series: [
        {
          type: 'bar',
          data: rows.map((r) => Number(r.pct.toFixed(1))),
          barMaxWidth: 18,
          itemStyle: { borderRadius: [0, 4, 4, 0] },
          label: {
            show: true,
            position: 'right',
            color: MUTED,
            formatter: (p) => {
              const row = rows[p.dataIndex]
              return `${number.format(row.bookedSlots)}/${number.format(row.totalSlots)}（${row.pct.toFixed(0)}%）`
            },
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
  paymentChart?.resize()
  classFillChart?.resize()
  memberGrowthChart?.resize()
  trainerUtilChart?.resize()
}

// ── 資料載入 ────────────────────────────────────────────────
/** 迄日 +1 天的 00:00，湊出與後端一致的半開區間 [from, to) */
function nextDayStart(dateStr) {
  const d = new Date(`${dateStr}T00:00:00`)
  d.setDate(d.getDate() + 1)
  return `${isoDate(d)}T00:00:00`
}

async function fetchStats() {
  statsLoading.value = true
  statsError.value = null
  try {
    const [statsRes, overviewRes, fillRes, growthRes, utilRes, effectRes] = await Promise.all([
      getLiveStats({
        from: from.value,
        to: to.value,
        type: typeFilter.value,
        top: 10,
      }),
      // overview 不分品項型別（訂單層級的統計切不開型別），只跟期間篩選連動
      getOrderOverview({ from: `${from.value}T00:00:00`, to: nextDayStart(to.value) }),
      getGroupClassFill({ from: from.value, to: to.value }),
      getMemberGrowth({ from: from.value, to: to.value }),
      getTrainerUtilization({ from: from.value, to: to.value }),
      getCouponEffect(),
    ])
    stats.value = statsRes.data
    overview.value = overviewRes.data
    classFill.value = fillRes.data ?? []
    memberDaily.value = growthRes.data ?? []
    trainerUtil.value = utilRes.data ?? []
    couponEffect.value = effectRes.data ?? []
    // 等 nextTick 讓容器套用新的 rankingHeight 後再 resize，否則圖表仍用舊高度繪製
    await nextTick()
    if (hasRanking.value) {
      rankingChart?.resize()
      renderRanking()
    }
    if (hasPaymentData.value) renderPaymentPie()
    if (hasClassFill.value) {
      classFillChart?.resize()
      renderClassFill()
    }
    if (hasMemberDaily.value) renderMemberGrowth()
    if (hasTrainerUtil.value) {
      trainerUtilChart?.resize()
      renderTrainerUtil()
    }
  } catch (e) {
    statsError.value = e.message
  } finally {
    statsLoading.value = false
  }
}

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
    const { data: created } = await generateReport({ reportType: genReportType.value, periodStart, periodEnd })
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
  if (dailyTotals.value.length > 0) renderTrend()
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
  await downloadBlob(() => downloadReportPdf(currentReportId()), 'application/pdf', 'pdf', (v) => (downloadingPdf.value = v))
}

const downloadingCsv = ref(false)
async function onDownloadCsv() {
  await downloadBlob(() => downloadReportCsv(currentReportId()), 'text/csv;charset=utf-8', 'csv',
    (v) => (downloadingCsv.value = v))
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
  paymentChart?.dispose()
  classFillChart?.dispose()
  memberGrowthChart?.dispose()
  trainerUtilChart?.dispose()
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
        <span class="filter__label">期間</span>
        <select v-model="rangePreset" class="filter__input" @change="onPresetChange">
          <option v-for="p in RANGE_PRESETS" :key="p.value" :value="p.value">{{ p.label }}</option>
        </select>
      </label>
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

    <!-- 付款方式分布：即時統計，各方式的訂單數佔比（口徑：下單日，與上方營收的付款日不同） -->
    <section class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">付款方式分布</h2>
        <span class="chart-card__sub">{{ formatDate(from) }} – {{ formatDate(to) }}．依訂單數</span>
      </header>
      <p v-if="!statsLoading && !hasPaymentData" class="empty-hint">此期間沒有付款資料。</p>
      <div v-show="hasPaymentData" ref="paymentEl" class="chart" style="height: 300px"></div>
    </section>

    <!-- 團課滿座率：健身房營運最關心的供需指標，軸固定 0–100% -->
    <section class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">團課滿座率</h2>
        <span class="chart-card__sub">{{ formatDate(from) }} – {{ formatDate(to) }}．依場次開始時間，取消場次不計</span>
      </header>
      <p v-if="!statsLoading && !hasClassFill" class="empty-hint">此期間沒有團課場次。</p>
      <div
        v-show="hasClassFill"
        ref="classFillEl"
        class="chart"
        :style="{ height: `${classFillHeight}px` }"
      ></div>
    </section>

    <!-- 每日新會員：成長趨勢，無人註冊的日期後端不回列（斷點如實呈現） -->
    <section class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">每日新會員</h2>
        <span class="chart-card__sub">{{ formatDate(from) }} – {{ formatDate(to) }}．依註冊日</span>
      </header>
      <p v-if="!statsLoading && !hasMemberDaily" class="empty-hint">此期間沒有新註冊會員。</p>
      <div v-show="hasMemberDaily" ref="memberGrowthEl" class="chart" style="height: 280px"></div>
    </section>

    <!-- 教練時段使用率：私教供需，軸固定 0–100% -->
    <section class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">教練時段使用率</h2>
        <span class="chart-card__sub">{{ formatDate(from) }} – {{ formatDate(to) }}．依時段開始時間，取消時段不計</span>
      </header>
      <p v-if="!statsLoading && !hasTrainerUtil" class="empty-hint">此期間沒有教練開放時段。</p>
      <div
        v-show="hasTrainerUtil"
        ref="trainerUtilEl"
        class="chart"
        :style="{ height: `${trainerUtilHeight}px` }"
      ></div>
    </section>

    <!-- 優惠券成效：多欄數值用表格比圖表誠實，比率已在 computed 算好 -->
    <section class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">優惠券成效</h2>
        <span class="chart-card__sub">券的整個生命週期，不隨期間篩選</span>
      </header>
      <p v-if="!statsLoading && couponRows.length === 0" class="empty-hint">目前沒有優惠券。</p>
      <div v-if="couponRows.length > 0" class="effect-table-wrap">
        <table class="effect-table">
          <thead>
            <tr>
              <th>名稱</th>
              <th>代碼</th>
              <th class="num">發行</th>
              <th class="num">已領取</th>
              <th class="num">領取率</th>
              <th class="num">已使用</th>
              <th class="num">使用率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in couponRows" :key="c.code">
              <td>{{ c.name }}</td>
              <td class="code">{{ c.code }}</td>
              <td class="num">{{ number.format(c.totalQuantity ?? 0) }}</td>
              <td class="num">{{ number.format(c.claimedQuantity ?? 0) }}</td>
              <td class="num">{{ c.claimRate === '—' ? '—' : `${c.claimRate}%` }}</td>
              <td class="num">{{ number.format(c.usedCount ?? 0) }}</td>
              <td class="num">{{ c.useRate === '—' ? '—' : `${c.useRate}%` }}</td>
            </tr>
          </tbody>
        </table>
      </div>
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
                {{ formatDate(r.periodEnd) }}{{ r.voidedAt ? '｜已作廢' : '' }}
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
          同類型、同區間若已有快照，這次的結果會成為現行版；舊的一份保留為歷史版本，
          可從「版本」選單回看（append-only，當初的數字永遠可追溯）。
        </p>
        <OperationMessage :message="generateError" />
      </div>

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

    <section v-if="paymentBreakdown.length > 0" class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">付款方式分佈</h2>
        <span class="chart-card__sub">依所選報表快照</span>
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
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem;
  margin-left: auto;
}

.report-picker__field {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}

/* 標籤不可折行：擠不下就整組換到下一列，不要「報表快／照」逐字疊起來 */
.report-picker .filter__label {
  white-space: nowrap;
}

/* 版本下拉固定寬度：一版與多版、現行版與歷史版的文字長度不同，寬度變了按鈕也會跳 */
.report-picker__version {
  width: 16rem;
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

.stale-notice--void {
  background: #fde8e6;
  color: #a12f24;
}

.report-picker__check {
  gap: 0.35rem;
  cursor: pointer;
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

/* 優惠券成效表：窄螢幕時讓表格自己橫向捲，不撐破卡片 */
.effect-table-wrap {
  overflow-x: auto;
}

.effect-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9rem;
}

.effect-table th,
.effect-table td {
  padding: 0.55rem 0.75rem;
  border-bottom: 1px solid var(--border);
  text-align: left;
  white-space: nowrap;
}

.effect-table th {
  color: var(--text-secondary);
  font-weight: 600;
}

.effect-table .num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.effect-table .code {
  font-family: monospace;
  color: var(--text-secondary);
}

.effect-table tbody tr:last-child td {
  border-bottom: none;
}
</style>
