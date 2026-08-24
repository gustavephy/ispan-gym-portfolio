<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
// 只引入用得到的部分（折線圖＋直角座標系＋tooltip／legend＋canvas 渲染器），
// 避免整包 echarts（含地圖、3D 等用不到的模組）都被打進 bundle
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import {
  getInbodyHistory,
  createInbodyRecord,
  updateInbodyRecord,
  deleteInbodyRecord,
} from '@/api/aihealthchat/inbody.js'
import { notify } from '@/api/error-bus.js'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const FIELDS = ['height', 'weight', 'bodyFat', 'muscleMass', 'bmi']
const FIELD_LABELS = { height: '身高 (cm)', weight: '體重 (kg)', bodyFat: '體脂率 (%)', muscleMass: '肌肉量 (kg)', bmi: 'BMI' }

const records = ref([])
const loading = ref(true)
const listError = ref('')

const today = new Date().toISOString().slice(0, 10)
const editingId = ref(null)
const submitting = ref(false)
const formError = ref('')
const form = reactive(emptyForm())

function emptyForm() {
  return { updateDate: today, height: '', weight: '', bodyFat: '', muscleMass: '', bmi: '' }
}

function toNumberOrNull(v) {
  if (v === '' || v === null || v === undefined) return null
  const n = Number(v)
  return Number.isNaN(n) ? null : n
}

function buildPayload() {
  return {
    updateDate: form.updateDate,
    height: toNumberOrNull(form.height),
    weight: toNumberOrNull(form.weight),
    bodyFat: toNumberOrNull(form.bodyFat),
    muscleMass: toNumberOrNull(form.muscleMass),
    bmi: toNumberOrNull(form.bmi),
  }
}

async function loadHistory() {
  loading.value = true
  listError.value = ''
  try {
    const { data } = await getInbodyHistory()
    records.value = data
  } catch (e) {
    listError.value = e.message
  } finally {
    loading.value = false
  }
}

function startEdit(record) {
  editingId.value = record.id
  formError.value = ''
  for (const field of FIELDS) {
    form[field] = record[field] ?? ''
  }
  form.updateDate = record.updateDate
}

function cancelEdit() {
  editingId.value = null
  formError.value = ''
  Object.assign(form, emptyForm())
}

async function onSubmit() {
  formError.value = ''
  if (FIELDS.every((f) => toNumberOrNull(form[f]) === null)) {
    formError.value = '五項量測值請至少填寫一項'
    return
  }
  submitting.value = true
  try {
    const payload = buildPayload()
    if (editingId.value) {
      await updateInbodyRecord(editingId.value, payload)
      notify('已更新', 'InBody 紀錄已更新', 'success')
    } else {
      await createInbodyRecord(payload)
      notify('已新增', 'InBody 紀錄已新增', 'success')
    }
    cancelEdit()
    await loadHistory()
  } catch (e) {
    formError.value = e.message
  } finally {
    submitting.value = false
  }
}

async function onDelete(record) {
  if (!window.confirm(`確定要刪除 ${record.updateDate} 的 InBody 紀錄嗎？`)) return
  try {
    await deleteInbodyRecord(record.id)
    notify('已刪除', 'InBody 紀錄已刪除', 'success')
    if (editingId.value === record.id) cancelEdit()
    await loadHistory()
  } catch (e) {
    notify('刪除失敗', e.message, 'error')
  }
}

// ── 趨勢圖：體重／體脂率／肌肉量，兩個 y 軸（kg 一軸、% 一軸），只有 >=2 筆非 null 才畫該項 ──
const chartEl = ref(null)
let chart = null

const chartSeries = computed(() => {
  const asc = [...records.value].sort((a, b) => a.updateDate.localeCompare(b.updateDate))
  const dates = asc.map((r) => r.updateDate)
  const pick = (field) => asc.map((r) => r[field] ?? null)

  const defs = [
    { field: 'weight', name: '體重 (kg)', axis: 0, color: '#c99a5b' },
    { field: 'bodyFat', name: '體脂率 (%)', axis: 1, color: '#8a6d5c' },
    { field: 'muscleMass', name: '肌肉量 (kg)', axis: 0, color: '#5a4a42' },
  ]
  const usable = defs
    .map((d) => ({ ...d, data: pick(d.field) }))
    .filter((d) => d.data.filter((v) => v !== null).length >= 2)

  return { dates, usable }
})

const hasChartData = computed(() => chartSeries.value.usable.length > 0)

function renderChart() {
  if (!chartEl.value) return
  if (!chart) chart = echarts.init(chartEl.value)
  const { dates, usable } = chartSeries.value

  chart.setOption({
    color: usable.map((s) => s.color),
    tooltip: { trigger: 'axis' },
    legend: { data: usable.map((s) => s.name), top: 0 },
    grid: { left: 50, right: 50, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: dates },
    yAxis: [
      { type: 'value', name: 'kg', position: 'left' },
      { type: 'value', name: '%', position: 'right' },
    ],
    series: usable.map((s) => ({
      name: s.name,
      type: 'line',
      yAxisIndex: s.axis,
      data: s.data,
      connectNulls: false,
      smooth: true,
    })),
  })
}

watch(chartSeries, () => nextTick(renderChart))

function onResize() {
  chart?.resize()
}

onMounted(async () => {
  await loadHistory()
  await nextTick()
  if (hasChartData.value) renderChart()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="inbody-panel">
    <section class="card">
      <h2 class="card__title">{{ editingId ? '編輯 InBody 紀錄' : '新增 InBody 紀錄' }}</h2>
      <form class="inbody-form" @submit.prevent="onSubmit">
        <label class="inbody-form__field">
          <span>量測日期</span>
          <input v-model="form.updateDate" type="date" :max="today" required />
        </label>
        <label v-for="field in FIELDS" :key="field" class="inbody-form__field">
          <span>{{ FIELD_LABELS[field] }}</span>
          <input v-model="form[field]" type="number" step="0.01" placeholder="未量測可留空" />
        </label>

        <p v-if="formError" class="inline-error" role="alert">{{ formError }}</p>

        <div class="inbody-form__actions">
          <button type="submit" class="btn btn-accent" :disabled="submitting">
            {{ submitting ? '送出中…' : editingId ? '儲存修改' : '新增紀錄' }}
          </button>
          <button v-if="editingId" type="button" class="btn btn-outline" @click="cancelEdit">
            取消編輯
          </button>
        </div>
      </form>
    </section>

    <section class="card">
      <h2 class="card__title">歷史趨勢</h2>
      <div v-if="hasChartData" ref="chartEl" class="chart"></div>
      <p v-else class="empty-hint">量測紀錄不足，至少需要兩筆同項目的資料才能繪製趨勢圖</p>
    </section>

    <section class="card">
      <h2 class="card__title">歷史紀錄</h2>
      <div v-if="loading" class="spinner"></div>
      <p v-else-if="listError" class="inline-error">{{ listError }}</p>
      <p v-else-if="records.length === 0" class="empty-hint">尚無任何 InBody 紀錄，新增第一筆吧！</p>
      <div v-else class="table-wrap">
        <table class="inbody-table">
          <thead>
            <tr>
              <th>日期</th>
              <th v-for="f in FIELDS" :key="f">{{ FIELD_LABELS[f] }}</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in records" :key="r.id">
              <td>{{ r.updateDate }}</td>
              <td v-for="f in FIELDS" :key="f">{{ r[f] ?? '—' }}</td>
              <td class="inbody-table__actions">
                <button type="button" class="link-btn" @click="startEdit(r)">編輯</button>
                <button type="button" class="link-btn link-btn--danger" @click="onDelete(r)">刪除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.inbody-panel {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.card {
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 1.5rem;
}

.card__title {
  font-size: 1.15rem;
  font-weight: 800;
  color: var(--primary-dark);
  margin-bottom: 1rem;
}

.inbody-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 1rem;
  align-items: end;
}

.inbody-form__field {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.inbody-form__field input {
  padding: 0.5rem 0.65rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.95rem;
}

.inbody-form__actions {
  grid-column: 1 / -1;
  display: flex;
  gap: 0.75rem;
}

.inline-error {
  grid-column: 1 / -1;
  color: #c0392b;
  font-size: 0.85rem;
}

.chart {
  width: 100%;
  height: 320px;
}

.empty-hint {
  color: var(--text-muted);
  font-size: 0.9rem;
  padding: 1rem 0;
}

.table-wrap {
  overflow-x: auto;
}

.inbody-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9rem;
}

.inbody-table th,
.inbody-table td {
  padding: 0.6rem 0.75rem;
  text-align: left;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

.inbody-table th {
  color: var(--text-secondary);
  font-weight: 700;
}

.inbody-table__actions {
  display: flex;
  gap: 0.75rem;
}

.link-btn {
  border: none;
  background: none;
  color: var(--accent);
  font-weight: 600;
  cursor: pointer;
  padding: 0;
}

.link-btn:hover {
  text-decoration: underline;
}

.link-btn--danger {
  color: #c0392b;
}
</style>
