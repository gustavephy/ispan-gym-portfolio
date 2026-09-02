<script setup>
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
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
const reqRateSeries = ref([])
const p50Series = ref([])
const p95Series = ref([])
const p99Series = ref([])
const poolActiveSeries = ref([])
const poolIdleSeries = ref([])

// 後端給的是「啟動以來」的累積計數（counter），速率與錯誤率要用相鄰兩次輪詢的差分算
// ——跟 Prometheus 的 rate() 是同一件事，只是視窗短
let prevSample = null
const reqPerSec = ref(0)
const errRatePct = ref(0)

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
      tone: heapPct.value > 85 ? 'down' : heapPct.value > 70 ? 'warn' : 'ok',
    },
    {
      key: 'cpu',
      label: '應用程式 CPU',
      value: `${(Number(m.processCpuUsage ?? 0) * 100).toFixed(1)}%`,
      hint: `系統 ${(Number(m.systemCpuUsage ?? 0) * 100).toFixed(1)}%`,
      tone: m.processCpuUsage > 0.85 ? 'down' : m.processCpuUsage > 0.6 ? 'warn' : 'ok',
    },
    { key: 'threads', label: '執行緒', value: number.format(m.liveThreads ?? 0) },
    {
      key: 'pool',
      label: 'DB 連線池',
      value: `${m.dbPoolActive ?? 0} / ${m.dbPoolMax ?? 0}`,
      hint: `閒置 ${m.dbPoolIdle ?? 0}`,
      tone: m.dbPoolMax && m.dbPoolActive >= m.dbPoolMax ? 'down' : null,
    },
    {
      key: 'rate',
      label: '每秒請求數',
      value: reqPerSec.value.toFixed(1),
      hint: `累計 ${number.format(m.httpRequestCount ?? 0)} 次`,
    },
    {
      key: 'p95',
      label: 'p95 延遲',
      value: `${Number(m.httpP95Millis ?? 0).toFixed(0)} ms`,
      hint: `p50 ${Number(m.httpP50Millis ?? 0).toFixed(0)}／p99 ${Number(m.httpP99Millis ?? 0).toFixed(0)} ms`,
      tone: m.httpP95Millis > 1000 ? 'down' : m.httpP95Millis > 500 ? 'warn' : 'ok',
    },
    {
      key: 'err',
      label: '5xx 錯誤率',
      value: `${errRatePct.value.toFixed(1)}%`,
      hint: `累計 ${number.format(m.http5xxCount ?? 0)} 次`,
      tone: errRatePct.value > 0 ? 'down' : 'ok',
    },
  ]
})

const maxEndpointCount = computed(() => {
  const list = metrics.value?.topEndpoints ?? []
  return list.reduce((max, e) => Math.max(max, e.count), 0)
})

// ── 圖表 ────────────────────────────────────────────────────
// Grafana 經典色盤與深色面板配色
const GREEN = '#73bf69'
const YELLOW = '#fade2a'
const RED = '#f2495c'
const BLUE = '#5794f2'
const ORANGE = '#ff9830'
const PURPLE = '#b877d9'
const TEXT_MUTED = 'rgba(204, 204, 220, 0.65)'
const GRID_LINE = 'rgba(204, 204, 220, 0.1)'

const latencyEl = ref(null)
const rateEl = ref(null)
const heapEl = ref(null)
const cpuEl = ref(null)
const poolEl = ref(null)
const charts = {}

function lineOption({ seriesDefs, unit = '', min, max, hasLegend }) {
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', valueFormatter: (v) => `${Number(v ?? 0).toFixed(1)}${unit}` },
    legend: hasLegend ? { top: 0, textStyle: { color: TEXT_MUTED } } : undefined,
    grid: { left: 48, right: 12, top: hasLegend ? 30 : 14, bottom: 26 },
    xAxis: {
      type: 'category',
      data: sampleTimes.value,
      axisLine: { lineStyle: { color: GRID_LINE } },
      axisLabel: { color: TEXT_MUTED },
    },
    yAxis: {
      type: 'value',
      min,
      max,
      axisLabel: { color: TEXT_MUTED, formatter: `{value}${unit}` },
      splitLine: { lineStyle: { color: GRID_LINE } },
    },
    series: seriesDefs,
  }
}

function line(name, data, extra = {}) {
  return { name, type: 'line', data, showSymbol: false, lineStyle: { width: 2 }, ...extra }
}

function setChart(key, el, option) {
  if (!el) return
  if (!charts[key]) charts[key] = echarts.init(el)
  charts[key].setOption(option, true)
}

function renderCharts() {
  setChart('latency', latencyEl.value, {
    color: [GREEN, YELLOW, RED],
    ...lineOption({
      unit: 'ms',
      min: 0,
      hasLegend: true,
      seriesDefs: [
        line('p50', p50Series.value),
        line('p95', p95Series.value),
        line('p99', p99Series.value),
      ],
    }),
  })
  setChart('rate', rateEl.value, {
    color: [BLUE],
    ...lineOption({
      min: 0,
      seriesDefs: [line('req/s', reqRateSeries.value, { areaStyle: { opacity: 0.12 } })],
    }),
  })
  setChart('heap', heapEl.value, {
    color: [PURPLE],
    ...lineOption({
      unit: '%',
      min: 0,
      max: 100,
      seriesDefs: [line('Heap 使用率', heapSeries.value, { areaStyle: { opacity: 0.1 } })],
    }),
  })
  setChart('cpu', cpuEl.value, {
    color: [BLUE, ORANGE],
    ...lineOption({
      unit: '%',
      min: 0,
      max: 100,
      hasLegend: true,
      seriesDefs: [
        line('應用程式 CPU', cpuProcessSeries.value),
        line('系統 CPU', cpuSystemSeries.value),
      ],
    }),
  })
  setChart('pool', poolEl.value, {
    color: [GREEN, BLUE, RED],
    ...lineOption({
      min: 0,
      max: (metrics.value?.dbPoolMax ?? 10) + 2,
      hasLegend: true,
      seriesDefs: [
        line('使用中', poolActiveSeries.value),
        line('閒置', poolIdleSeries.value),
        line('上限', Array(sampleTimes.value.length).fill(metrics.value?.dbPoolMax ?? 0), {
          lineStyle: { width: 1, type: 'dashed' },
        }),
      ],
    }),
  })
}

const allSeries = [
  sampleTimes, heapSeries, cpuProcessSeries, cpuSystemSeries,
  reqRateSeries, p50Series, p95Series, p99Series, poolActiveSeries, poolIdleSeries,
]

function pushSample(m) {
  const now = new Date()

  // counter 差分 → 速率（後端重啟計數歸零會出現負差，視為 0）
  const nowMs = now.getTime()
  if (prevSample) {
    const dt = (nowMs - prevSample.t) / 1000
    const dCount = m.httpRequestCount - prevSample.count
    const d5xx = m.http5xxCount - prevSample.fiveXx
    reqPerSec.value = dt > 0 && dCount > 0 ? dCount / dt : 0
    errRatePct.value = dCount > 0 && d5xx > 0 ? (d5xx / dCount) * 100 : 0
  }
  prevSample = { t: nowMs, count: m.httpRequestCount ?? 0, fiveXx: m.http5xxCount ?? 0 }

  sampleTimes.value.push(
    `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`,
  )
  heapSeries.value.push(Number((m.heapMaxBytes ? (m.heapUsedBytes / m.heapMaxBytes) * 100 : 0).toFixed(1)))
  cpuProcessSeries.value.push(Number((Number(m.processCpuUsage ?? 0) * 100).toFixed(1)))
  cpuSystemSeries.value.push(Number((Number(m.systemCpuUsage ?? 0) * 100).toFixed(1)))
  reqRateSeries.value.push(Number(reqPerSec.value.toFixed(2)))
  p50Series.value.push(Number(Number(m.httpP50Millis ?? 0).toFixed(1)))
  p95Series.value.push(Number(Number(m.httpP95Millis ?? 0).toFixed(1)))
  p99Series.value.push(Number(Number(m.httpP99Millis ?? 0).toFixed(1)))
  poolActiveSeries.value.push(m.dbPoolActive ?? 0)
  poolIdleSeries.value.push(m.dbPoolIdle ?? 0)
  if (sampleTimes.value.length > MAX_POINTS) {
    allSeries.forEach((s) => s.value.shift())
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
    // 第一筆資料到手時圖表容器才剛從 v-if 長出來，等 DOM 更新完再 init
    await nextTick()
    renderCharts()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function resizeCharts() {
  Object.values(charts).forEach((c) => c.resize())
}

onMounted(async () => {
  window.addEventListener('resize', resizeCharts)
  await fetchMetrics()
  pollTimer = setInterval(fetchMetrics, POLL_MS)
})

onBeforeUnmount(() => {
  clearInterval(pollTimer)
  window.removeEventListener('resize', resizeCharts)
  Object.values(charts).forEach((c) => c.dispose())
})
</script>

<template>
  <div class="page-container system-monitor-page">
    <span class="section-tag">ADMIN</span>
    <h1 class="section-title">系統監控</h1>
    <p class="section-desc">
      服務健康狀態與資源使用情形，每 {{ POLL_MS / 1000 }} 秒自動更新；
      延遲分位數由直方圖插值（與 Grafana／PromQL 同款算法），速率由計數器差分而來。
    </p>

    <OperationMessage :message="error" />
    <div v-if="loading" class="spinner"></div>

    <div v-else-if="metrics" class="graf-board">
      <!-- 狀態卡列 -->
      <div class="tile-row">
        <div
          v-for="t in statusTiles"
          :key="t.key"
          class="tile"
          :class="{ 'tile--ok': t.tone === 'ok', 'tile--down': t.tone === 'down', 'tile--warn': t.tone === 'warn' }"
        >
          <span class="tile__label">{{ t.label }}</span>
          <strong class="tile__value">{{ t.value }}</strong>
          <span v-if="t.hint" class="tile__hint">{{ t.hint }}</span>
        </div>
      </div>

      <!-- 即時折線：最近 5 分鐘滾動視窗 -->
      <div class="panel-grid">
        <section class="panel">
          <h2 class="panel__title">HTTP 延遲分位數（ms）</h2>
          <div ref="latencyEl" class="chart" style="height: 220px"></div>
        </section>
        <section class="panel">
          <h2 class="panel__title">每秒請求數</h2>
          <div ref="rateEl" class="chart" style="height: 220px"></div>
        </section>
        <section class="panel">
          <h2 class="panel__title">JVM Heap 使用率</h2>
          <div ref="heapEl" class="chart" style="height: 220px"></div>
        </section>
        <section class="panel">
          <h2 class="panel__title">CPU 使用率</h2>
          <div ref="cpuEl" class="chart" style="height: 220px"></div>
        </section>
        <section class="panel">
          <h2 class="panel__title">DB 連線池（HikariCP）</h2>
          <div ref="poolEl" class="chart" style="height: 220px"></div>
        </section>
        <section class="panel">
          <h2 class="panel__title">流量前五名端點（不含本頁輪詢）</h2>
          <p v-if="!metrics.topEndpoints?.length" class="panel__empty">還沒有流量</p>
          <table v-else class="ep-table">
            <thead>
              <tr>
                <th>端點</th>
                <th class="num">請求數</th>
                <th class="num">平均延遲</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="e in metrics.topEndpoints" :key="e.uri">
                <td class="uri">
                  <span
                    class="uri__bar"
                    :style="{ width: maxEndpointCount ? `${(e.count / maxEndpointCount) * 100}%` : '0%' }"
                  ></span>
                  <code>{{ e.uri }}</code>
                </td>
                <td class="num">{{ number.format(e.count) }}</td>
                <td class="num">{{ Number(e.avgMillis ?? 0).toFixed(1) }} ms</td>
              </tr>
            </tbody>
          </table>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.system-monitor-page {
  padding-bottom: 2rem;
}

/* Grafana 風深色儀表板：獨立於後台淺色主題之外的一塊深色板 */
.graf-board {
  background: #111217;
  border: 1px solid #2c3235;
  border-radius: 10px;
  padding: 1rem;
  color: #ccccdc;
}

.tile-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}

.tile {
  background: #181b1f;
  border: 1px solid #23262b;
  border-radius: 6px;
  padding: 0.75rem 0.9rem;
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}

.tile__label {
  font-size: 0.75rem;
  color: rgba(204, 204, 220, 0.65);
}

.tile__value {
  font-size: 1.35rem;
  font-weight: 800;
  color: #ccccdc;
  font-variant-numeric: tabular-nums;
}

.tile__hint {
  font-size: 0.72rem;
  color: rgba(204, 204, 220, 0.45);
}

/* Grafana stat panel 的健康色直接染在數值上 */
.tile--ok .tile__value {
  color: #73bf69;
}

.tile--warn .tile__value {
  color: #fade2a;
}

.tile--down .tile__value {
  color: #f2495c;
}

.panel-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 0.75rem;
}

.panel {
  background: #181b1f;
  border: 1px solid #23262b;
  border-radius: 6px;
  padding: 0.9rem 1rem;
  min-width: 0;
}

.panel__title {
  font-size: 0.82rem;
  font-weight: 600;
  color: rgba(204, 204, 220, 0.8);
  margin: 0 0 0.5rem;
}

.panel__empty {
  color: rgba(204, 204, 220, 0.45);
  font-size: 0.85rem;
  margin: 2rem 0;
  text-align: center;
}

.chart {
  width: 100%;
}

.ep-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.85rem;
}

.ep-table th {
  text-align: left;
  font-size: 0.72rem;
  font-weight: 600;
  color: rgba(204, 204, 220, 0.55);
  padding: 0.4rem 0.5rem;
  border-bottom: 1px solid #23262b;
}

.ep-table td {
  padding: 0.45rem 0.5rem;
  border-bottom: 1px solid rgba(204, 204, 220, 0.06);
}

.ep-table .num {
  text-align: right;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* uri 欄背後鋪一條與請求數成比例的橫條，Grafana table 的 gauge 欄位風 */
.ep-table .uri {
  position: relative;
}

.uri__bar {
  position: absolute;
  left: 0;
  top: 15%;
  height: 70%;
  background: rgba(87, 148, 242, 0.16);
  border-radius: 3px;
  pointer-events: none;
}

.ep-table code {
  position: relative;
  font-size: 0.8rem;
  color: #ccccdc;
  background: none;
  padding: 0;
}
</style>
