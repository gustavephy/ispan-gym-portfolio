<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
// 按需引入 echarts，比照 AdminReportView.vue
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getSystemMetrics } from '@/api/adminSystem.js'
import OperationMessage from '@/components/OperationMessage.vue'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const POLL_MS = 5000
/** 滾動視窗保留的取樣點數：5 秒一點 × 60 點 ＝ 最近 5 分鐘 */
const MAX_POINTS = 60

const metrics = ref(null)
const error = ref(null)
const loading = ref(true)

// 滾動取樣：時間戳與各序列平行存放，超過視窗就從頭移除
const sampleTimes = ref([])
const heapSeries = ref([])
const cpuProcessSeries = ref([])
const cpuSystemSeries = ref([])

const number = new Intl.NumberFormat('zh-TW')

function formatBytes(bytes) {
  const mb = Number(bytes ?? 0) / (1024 * 1024)
  return mb >= 1024 ? `${(mb / 1024).toFixed(2)} GB` : `${mb.toFixed(0)} MB`
}

function formatUptime(seconds) {
  const s = Number(seconds ?? 0)
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  if (d > 0) return `${d} 天 ${h} 小時`
  if (h > 0) return `${h} 小時 ${m} 分`
  return `${m} 分 ${Math.floor(s % 60)} 秒`
}

const heapPct = computed(() => {
  const m = metrics.value
  if (!m || !m.heapMaxBytes) return 0
  return (m.heapUsedBytes / m.heapMaxBytes) * 100
})

const statusTiles = computed(() => {
  const m = metrics.value
  if (!m) return []
  return [
    {
      key: 'db',
      label: '資料庫',
      value: m.dbStatus,
      hint: m.dbPingMillis != null ? `ping ${m.dbPingMillis} ms` : null,
      tone: m.dbStatus === 'UP' ? 'ok' : 'down',
    },
    { key: 'uptime', label: '已運行', value: formatUptime(m.uptimeSeconds), hint: `啟動於 ${String(m.startedAt ?? '').replace('T', ' ').slice(0, 19)}` },
    {
      key: 'heap',
      label: 'JVM Heap',
      value: `${heapPct.value.toFixed(1)}%`,
      hint: `${formatBytes(m.heapUsedBytes)} / ${formatBytes(m.heapMaxBytes)}`,
      tone: heapPct.value > 85 ? 'warn' : null,
    },
    { key: 'threads', label: '執行緒', value: number.format(m.liveThreads ?? 0) },
    {
      key: 'pool',
      label: 'DB 連線池',
      value: `${m.dbPoolActive ?? 0} 使用中`,
      hint: `閒置 ${m.dbPoolIdle ?? 0}／上限 ${m.dbPoolMax ?? 0}`,
    },
    {
      key: 'http',
      label: 'HTTP 請求',
      value: number.format(m.httpRequestCount ?? 0),
      hint: `平均延遲 ${Number(m.httpAvgLatencyMillis ?? 0).toFixed(1)} ms`,
    },
  ]
})

// ── 圖表 ────────────────────────────────────────────────────
const heapEl = ref(null)
const cpuEl = ref(null)
let heapChart = null
let cpuChart = null

const SERIES_1 = '#2a78d6'
const SERIES_2 = '#e8933d'
const MUTED = '#9a8f88'

function baseLineOption(seriesDefs) {
  return {
    tooltip: { trigger: 'axis', valueFormatter: (v) => `${Number(v ?? 0).toFixed(1)}%` },
    legend: seriesDefs.length > 1 ? { top: 0, textStyle: { color: MUTED } } : undefined,
    grid: { left: 44, right: 16, top: seriesDefs.length > 1 ? 32 : 16, bottom: 28 },
    xAxis: {
      type: 'category',
      data: sampleTimes.value,
      axisLine: { lineStyle: { color: MUTED } },
      axisLabel: { color: MUTED },
    },
    // 比例指標軸固定 0–100，跳動幅度才有一致的基準可比
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { color: MUTED, formatter: '{value}%' },
      splitLine: { lineStyle: { color: 'rgba(0,0,0,0.06)' } },
    },
    series: seriesDefs,
  }
}

function renderCharts() {
  if (heapEl.value) {
    if (!heapChart) heapChart = echarts.init(heapEl.value)
    heapChart.setOption(
      {
        color: [SERIES_1],
        ...baseLineOption([
          { name: 'Heap 使用率', type: 'line', data: heapSeries.value, showSymbol: false, lineStyle: { width: 2 }, areaStyle: { opacity: 0.08 } },
        ]),
      },
      true,
    )
  }
  if (cpuEl.value) {
    if (!cpuChart) cpuChart = echarts.init(cpuEl.value)
    cpuChart.setOption(
      {
        color: [SERIES_1, SERIES_2],
        ...baseLineOption([
          { name: '應用程式 CPU', type: 'line', data: cpuProcessSeries.value, showSymbol: false, lineStyle: { width: 2 } },
          { name: '系統 CPU', type: 'line', data: cpuSystemSeries.value, showSymbol: false, lineStyle: { width: 2 } },
        ]),
      },
      true,
    )
  }
}

function pushSample(m) {
  const now = new Date()
  sampleTimes.value.push(
    `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`,
  )
  heapSeries.value.push(Number((m.heapMaxBytes ? (m.heapUsedBytes / m.heapMaxBytes) * 100 : 0).toFixed(1)))
  cpuProcessSeries.value.push(Number((Number(m.processCpuUsage ?? 0) * 100).toFixed(1)))
  cpuSystemSeries.value.push(Number((Number(m.systemCpuUsage ?? 0) * 100).toFixed(1)))
  if (sampleTimes.value.length > MAX_POINTS) {
    sampleTimes.value.shift()
    heapSeries.value.shift()
    cpuProcessSeries.value.shift()
    cpuSystemSeries.value.shift()
  }
}

let pollTimer = null

async function fetchMetrics() {
  // 分頁被切到背景時暫停取樣，回到前景下一輪自然恢復，避免累積無意義的請求
  if (document.hidden) return
  try {
    const { data } = await getSystemMetrics()
    metrics.value = data
    error.value = null
    pushSample(data)
    renderCharts()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function resizeCharts() {
  heapChart?.resize()
  cpuChart?.resize()
}

onMounted(async () => {
  window.addEventListener('resize', resizeCharts)
  await fetchMetrics()
  pollTimer = setInterval(fetchMetrics, POLL_MS)
})

onBeforeUnmount(() => {
  clearInterval(pollTimer)
  window.removeEventListener('resize', resizeCharts)
  heapChart?.dispose()
  cpuChart?.dispose()
})
</script>

<template>
  <div class="page-container system-monitor-page">
    <span class="section-tag">ADMIN</span>
    <h1 class="section-title">系統監控</h1>
    <p class="section-desc">服務健康狀態與資源使用情形，每 {{ POLL_MS / 1000 }} 秒自動更新。</p>

    <OperationMessage :message="error" />
    <div v-if="loading" class="spinner"></div>

    <template v-else-if="metrics">
      <!-- 狀態卡列 -->
      <div class="tile-row">
        <div
          v-for="t in statusTiles"
          :key="t.key"
          class="tile card"
          :class="{ 'tile--ok': t.tone === 'ok', 'tile--down': t.tone === 'down', 'tile--warn': t.tone === 'warn' }"
        >
          <span class="tile__label">{{ t.label }}</span>
          <strong class="tile__value">{{ t.value }}</strong>
          <span v-if="t.hint" class="tile__hint">{{ t.hint }}</span>
        </div>
      </div>

      <!-- 即時折線：最近 5 分鐘滾動視窗 -->
      <section class="card chart-card">
        <header class="chart-card__header">
          <h2 class="chart-card__title">JVM Heap 使用率</h2>
          <span class="chart-card__sub">最近 5 分鐘．每 5 秒取樣</span>
        </header>
        <div ref="heapEl" class="chart" style="height: 240px"></div>
      </section>

      <section class="card chart-card">
        <header class="chart-card__header">
          <h2 class="chart-card__title">CPU 使用率</h2>
          <span class="chart-card__sub">應用程式與整機．最近 5 分鐘</span>
        </header>
        <div ref="cpuEl" class="chart" style="height: 240px"></div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.system-monitor-page {
  padding-bottom: 2rem;
}

.tile-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 1rem;
  margin-bottom: 1.5rem;
}

.tile {
  padding: 1rem 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.tile__label {
  font-size: 0.8rem;
  color: var(--text-secondary);
}

.tile__value {
  font-size: 1.4rem;
  font-weight: 800;
  color: var(--text-primary);
}

.tile__hint {
  font-size: 0.75rem;
  color: var(--text-muted);
}

/* 健康狀態用左框線提示，不整片染色 */
.tile--ok {
  border-left: 4px solid #43a06d;
}

.tile--ok .tile__value {
  color: #2e7d51;
}

.tile--down {
  border-left: 4px solid #c0392b;
}

.tile--down .tile__value {
  color: #c0392b;
}

.tile--warn {
  border-left: 4px solid #e8933d;
}

.chart-card {
  padding: 1.25rem;
  margin-bottom: 1.5rem;
}

.chart-card__header {
  display: flex;
  align-items: baseline;
  gap: 0.75rem;
  flex-wrap: wrap;
  margin-bottom: 0.75rem;
}

.chart-card__title {
  font-size: 1.05rem;
  margin: 0;
}

.chart-card__sub {
  font-size: 0.8rem;
  color: var(--text-muted);
}

.chart {
  width: 100%;
}
</style>
