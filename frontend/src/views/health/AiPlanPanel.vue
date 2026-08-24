<script setup>
import { ref, computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { getInbodyHistory } from '@/api/aihealthchat/inbody.js'
import { generatePlan, getPlanHistory, downloadPlanPdf } from '@/api/aihealthchat/aiPlan.js'
import { notify } from '@/api/error-bus.js'

const GOAL_OPTIONS = [
  { value: 'FAT_LOSS', label: '減脂' },
  { value: 'MUSCLE_GAIN', label: '增肌' },
  { value: 'MAINTAIN', label: '維持現狀' },
]

const inbodyRecords = ref([])
const loadingInbody = ref(true)

const selectedInbodyId = ref(null)
const selectedGoal = ref('FAT_LOSS')
const generating = ref(false)
const generateError = ref('')

const plans = ref([])
const loadingPlans = ref(true)
const plansError = ref('')
const expandedId = ref(null)
const downloadingId = ref(null)

async function loadInbody() {
  loadingInbody.value = true
  try {
    const { data } = await getInbodyHistory()
    inbodyRecords.value = data
    if (data.length > 0) selectedInbodyId.value = data[0].id
  } finally {
    loadingInbody.value = false
  }
}

async function loadPlans() {
  loadingPlans.value = true
  plansError.value = ''
  try {
    const { data } = await getPlanHistory()
    plans.value = data
  } catch (e) {
    plansError.value = e.message
  } finally {
    loadingPlans.value = false
  }
}

async function onGenerate() {
  generateError.value = ''
  if (!selectedInbodyId.value) {
    generateError.value = '請先選擇一筆 InBody 紀錄'
    return
  }
  generating.value = true
  try {
    await generatePlan({ inbodyRecordId: selectedInbodyId.value, goal: selectedGoal.value })
    notify('課表已生成', 'AI 已依你的 InBody 數據生成新課表', 'success')
    await loadPlans()
  } catch (e) {
    generateError.value = e.message
  } finally {
    generating.value = false
  }
}

function toggleExpand(planId) {
  expandedId.value = expandedId.value === planId ? null : planId
}

function parseContent(raw) {
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

const GOAL_LABELS = Object.fromEntries(GOAL_OPTIONS.map((g) => [g.value, g.label]))

async function onDownloadPdf(plan) {
  downloadingId.value = plan.id
  try {
    const { data } = await downloadPlanPdf(plan.id)
    const url = URL.createObjectURL(new Blob([data], { type: 'application/pdf' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `ai-health-plan-${plan.id}.pdf`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  } catch (e) {
    notify('下載失敗', e.message ?? 'PDF 下載失敗，請稍後再試', 'error')
  } finally {
    downloadingId.value = null
  }
}

onMounted(() => {
  loadInbody()
  loadPlans()
})
</script>

<template>
  <div class="ai-plan-panel">
    <section class="card">
      <h2 class="card__title">生成新課表</h2>

      <div v-if="loadingInbody" class="spinner"></div>
      <p v-else-if="inbodyRecords.length === 0" class="empty-hint">
        尚無 InBody 紀錄，請先前往
        <RouterLink to="/health/inbody" class="inline-link">InBody 紀錄</RouterLink>
        新增一筆量測資料。
      </p>
      <form v-else class="generate-form" @submit.prevent="onGenerate">
        <label class="generate-form__field">
          <span>依據的 InBody 紀錄</span>
          <select v-model="selectedInbodyId">
            <option v-for="r in inbodyRecords" :key="r.id" :value="r.id">
              {{ r.updateDate }}（體重 {{ r.weight ?? '—' }}kg／體脂 {{ r.bodyFat ?? '—' }}%）
            </option>
          </select>
        </label>
        <label class="generate-form__field">
          <span>訓練目標</span>
          <select v-model="selectedGoal">
            <option v-for="g in GOAL_OPTIONS" :key="g.value" :value="g.value">{{ g.label }}</option>
          </select>
        </label>

        <p v-if="generateError" class="inline-error">{{ generateError }}</p>

        <button type="submit" class="btn btn-accent" :disabled="generating">
          {{ generating ? 'AI 生成中，最長可能需要 2 分鐘…' : '生成課表' }}
        </button>
      </form>
    </section>

    <section class="card">
      <h2 class="card__title">課表歷史</h2>
      <div v-if="loadingPlans" class="spinner"></div>
      <p v-else-if="plansError" class="inline-error">{{ plansError }}</p>
      <p v-else-if="plans.length === 0" class="empty-hint">尚無任何課表，生成第一份試試看！</p>

      <ul v-else class="plan-list">
        <li v-for="plan in plans" :key="plan.id" class="plan-item">
          <button type="button" class="plan-item__header" @click="toggleExpand(plan.id)">
            <span class="plan-item__goal">{{ GOAL_LABELS[plan.goal] ?? plan.goal }}</span>
            <span class="plan-item__date">{{ plan.createdAt?.replace('T', ' ').slice(0, 16) }}</span>
            <span class="plan-item__toggle">{{ expandedId === plan.id ? '收合 ▲' : '展開 ▼' }}</span>
          </button>

          <div v-if="expandedId === plan.id" class="plan-item__body">
            <template v-if="parseContent(plan.planContent)">
              <p class="plan-summary">{{ parseContent(plan.planContent).summary }}</p>

              <div v-for="day in parseContent(plan.planContent).days" :key="day.day" class="plan-day">
                <h3 class="plan-day__title">第 {{ day.day }} 天 · {{ day.focus }}</h3>
                <table class="exercise-table">
                  <thead>
                    <tr><th>動作</th><th>組數</th><th>次數</th><th>備註</th></tr>
                  </thead>
                  <tbody>
                    <tr v-for="(ex, i) in day.exercises" :key="i">
                      <td>{{ ex.name }}</td>
                      <td>{{ ex.sets }}</td>
                      <td>{{ ex.reps }}</td>
                      <td>{{ ex.notes || '—' }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <div v-if="parseContent(plan.planContent).dietTips?.length" class="diet-tips">
                <h3 class="plan-day__title">飲食建議</h3>
                <ul>
                  <li v-for="(tip, i) in parseContent(plan.planContent).dietTips" :key="i">{{ tip }}</li>
                </ul>
              </div>
            </template>
            <pre v-else class="plan-raw">{{ plan.planContent }}</pre>

            <button
              type="button"
              class="btn btn-outline"
              :disabled="downloadingId === plan.id"
              @click="onDownloadPdf(plan)"
            >
              {{ downloadingId === plan.id ? '下載中…' : '下載 PDF' }}
            </button>
          </div>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.ai-plan-panel {
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

.empty-hint {
  color: var(--text-muted);
  font-size: 0.9rem;
}

.inline-link {
  color: var(--accent);
  font-weight: 700;
  text-decoration: underline;
}

.generate-form {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  align-items: end;
}

.generate-form__field {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  font-size: 0.85rem;
  color: var(--text-secondary);
  min-width: 220px;
}

.generate-form__field select {
  padding: 0.5rem 0.65rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.95rem;
}

.inline-error {
  flex-basis: 100%;
  color: #c0392b;
  font-size: 0.85rem;
}

.plan-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.plan-item {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.plan-item__header {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 0.75rem 1rem;
  background: var(--bg-soft);
  border: none;
  cursor: pointer;
  font-size: 0.9rem;
  text-align: left;
}

.plan-item__goal {
  font-weight: 800;
  color: var(--primary-dark);
}

.plan-item__date {
  color: var(--text-muted);
}

.plan-item__toggle {
  margin-left: auto;
  color: var(--accent);
  font-weight: 700;
}

.plan-item__body {
  padding: 1rem 1.25rem 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.plan-summary {
  color: var(--text-secondary);
  line-height: 1.6;
}

.plan-day__title {
  font-size: 1rem;
  font-weight: 700;
  color: var(--primary-dark);
  margin-bottom: 0.5rem;
}

.exercise-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.88rem;
}

.exercise-table th,
.exercise-table td {
  padding: 0.4rem 0.6rem;
  text-align: left;
  border-bottom: 1px solid var(--border);
}

.diet-tips ul {
  padding-left: 1.25rem;
  color: var(--text-secondary);
  line-height: 1.7;
}

.plan-raw {
  white-space: pre-wrap;
  font-size: 0.8rem;
  color: var(--text-secondary);
  background: var(--bg-soft);
  padding: 0.75rem;
  border-radius: var(--radius-sm);
}
</style>
