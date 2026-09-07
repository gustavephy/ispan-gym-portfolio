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
  getCategoryRevenue,
  getRevenueComposition,
  getGroupClassFill,
  getMemberGrowth,
  getTrainerUtilization,
  getCouponEffect,
} from '@/api/adminReport.js'
import OperationMessage from '@/components/OperationMessage.vue'
import { paymentMethodLabel } from '@/utils/paymentLabels.js'
import {
  isoDate,
  daysAgo,
  formatDate,
  currency,
  number,
  SERIES_1,
  MUTED,
  INK,
  CATEGORICAL,
} from '@/utils/reportFormat.js'

echarts.use([LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

// 品項型別對照：後端 ReportItemType
const TYPE_LABELS = {
  PRODUCT: '商品',
  COURSE: '課程',
  SUBSCRIPTION: '會籍',
}

// ── 篩選條件 ────────────────────────────────────────────────
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
const categoryRevenue = ref([])
const revenueComposition = ref([])

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
const hasCategoryRevenue = computed(() => categoryRevenue.value.length > 0)
const hasRevenueComposition = computed(() => revenueComposition.value.length > 0)
// 與排行同律：高度隨分類數成長
const categoryRevenueHeight = computed(
  () => Math.max(120, categoryRevenue.value.length * 44 + 32),
)
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

// ── 圖表 ────────────────────────────────────────────────────
const rankingEl = ref(null)
const paymentEl = ref(null)
const classFillEl = ref(null)
const memberGrowthEl = ref(null)
const trainerUtilEl = ref(null)
const categoryRevenueEl = ref(null)
const revenueCompositionEl = ref(null)
let rankingChart = null
let paymentChart = null
let classFillChart = null
let memberGrowthChart = null
let trainerUtilChart = null
let categoryRevenueChart = null
let revenueCompositionChart = null

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

function renderCategoryRevenue() {
  if (!categoryRevenueEl.value) return
  if (!categoryRevenueChart) categoryRevenueChart = echarts.init(categoryRevenueEl.value)
  // 營收由低到高排，最高的分類落在最上方（同滿座率、教練使用率的方向邏輯）
  const rows = [...categoryRevenue.value].sort(
    (a, b) => Number(a.grossAmount ?? 0) - Number(b.grossAmount ?? 0),
  )
  categoryRevenueChart.setOption(
    {
      color: [SERIES_1],
      tooltip: {
        trigger: 'item',
        formatter: (p) => {
          const row = rows[p.dataIndex]
          return `${row.categoryName}<br/>營收 ${currency.format(Number(row.grossAmount ?? 0))}<br/>售出 ${number.format(Number(row.quantitySold ?? 0))} 件`
        },
      },
      grid: { left: 8, right: 96, top: 8, bottom: 8, containLabel: true },
      xAxis: { type: 'value', show: false },
      yAxis: {
        type: 'category',
        data: rows.map((r) => r.categoryName),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: INK },
      },
      series: [
        {
          type: 'bar',
          data: rows.map((r) => Number(r.grossAmount ?? 0)),
          barMaxWidth: 18,
          itemStyle: { borderRadius: [0, 4, 4, 0] },
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

function renderRevenueComposition() {
  if (!revenueCompositionEl.value) return
  if (revenueCompositionChart && revenueCompositionChart.getDom() !== revenueCompositionEl.value) {
    revenueCompositionChart.dispose()
    revenueCompositionChart = null
  }
  if (!revenueCompositionChart) {
    revenueCompositionChart = echarts.init(revenueCompositionEl.value)
  }
  revenueCompositionChart.setOption(
    {
      color: CATEGORICAL,
      tooltip: {
        trigger: 'item',
        formatter: (p) => `${p.name}：${currency.format(p.value)}（${p.percent}%）`,
      },
      legend: { bottom: 0, textStyle: { color: INK } },
      series: [
        {
          type: 'pie',
          // 甜甜圈：規格同付款方式分布，兩張圓餅視覺一致
          radius: ['42%', '68%'],
          center: ['50%', '44%'],
          data: revenueComposition.value.map((r) => ({
            name: r.type,
            value: Number(r.grossAmount ?? 0),
          })),
          label: { color: INK, formatter: '{b} {d}%' },
        },
      ],
    },
    true,
  )
}

function resizeCharts() {
  rankingChart?.resize()
  paymentChart?.resize()
  classFillChart?.resize()
  memberGrowthChart?.resize()
  trainerUtilChart?.resize()
  categoryRevenueChart?.resize()
  revenueCompositionChart?.resize()
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
    const [
      statsRes,
      overviewRes,
      fillRes,
      growthRes,
      utilRes,
      effectRes,
      categoryRes,
      compositionRes,
    ] = await Promise.all([
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
      getCategoryRevenue({ from: from.value, to: to.value }),
      getRevenueComposition({ from: from.value, to: to.value }),
    ])
    stats.value = statsRes.data
    overview.value = overviewRes.data
    classFill.value = fillRes.data ?? []
    memberDaily.value = growthRes.data ?? []
    trainerUtil.value = utilRes.data ?? []
    couponEffect.value = effectRes.data ?? []
    categoryRevenue.value = categoryRes.data ?? []
    revenueComposition.value = compositionRes.data ?? []
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
    if (hasCategoryRevenue.value) {
      categoryRevenueChart?.resize()
      renderCategoryRevenue()
    }
    if (hasRevenueComposition.value) renderRevenueComposition()
  } catch (e) {
    statsError.value = e.message
  } finally {
    statsLoading.value = false
  }
}

function applyFilter() {
  fetchStats()
}

onMounted(() => {
  window.addEventListener('resize', resizeCharts)
  fetchStats()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  rankingChart?.dispose()
  paymentChart?.dispose()
  classFillChart?.dispose()
  memberGrowthChart?.dispose()
  trainerUtilChart?.dispose()
  categoryRevenueChart?.dispose()
  revenueCompositionChart?.dispose()
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

    <!-- 各商品分類營收：只含商品品項，私教課與訂閱沒有商品分類（佔比看下方營收組成） -->
    <section class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">各商品分類營收</h2>
        <span class="chart-card__sub">{{ formatDate(from) }} – {{ formatDate(to) }}．僅商品</span>
      </header>
      <p v-if="!statsLoading && !hasCategoryRevenue" class="empty-hint">此期間沒有商品營收。</p>
      <div
        v-show="hasCategoryRevenue"
        ref="categoryRevenueEl"
        class="chart"
        :style="{ height: `${categoryRevenueHeight}px` }"
      ></div>
    </section>

    <!-- 營收組成：商品／私人課程／會籍訂閱三分，補齊上一張圖看不到的非商品營收 -->
    <section class="card chart-card">
      <header class="chart-card__header">
        <h2 class="chart-card__title">營收組成</h2>
        <span class="chart-card__sub">{{ formatDate(from) }} – {{ formatDate(to) }}．依營收</span>
      </header>
      <p v-if="!statsLoading && !hasRevenueComposition" class="empty-hint">此期間沒有營收資料。</p>
      <div
        v-show="hasRevenueComposition"
        ref="revenueCompositionEl"
        class="chart"
        style="height: 300px"
      ></div>
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

/* 標籤不可折行：擠不下就整組換到下一列，不要「報表快／照」逐字疊起來 */
.chart {
  width: 100%;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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
