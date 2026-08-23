<script setup>
import { computed } from 'vue'

const props = defineProps({
  courseType: { type: String, default: null }, // 'PRIVATE' | 'GROUP'
  statusName: { type: String, default: null }, // 顯示文字 (如 "已預約")
  statusType: { type: String, default: null }  // 樣式類型 (如 "success")
})

const COURSE_TYPE_LABEL = { PRIVATE: '私教課', GROUP: '團課' }

const label = computed(() => {
  if (props.statusName) return props.statusName
  return COURSE_TYPE_LABEL[props.courseType] ?? '課程'
})

const badgeClass = computed(() => {
  if (props.statusType) return `status-${props.statusType}`
  return props.courseType === 'PRIVATE' ? 'type-private' : 'type-group'
})
</script>

<template>
  <span class="badge-pill" :class="badgeClass">{{ label }}</span>
</template>

<style scoped>
.badge-pill {
  display: inline-flex;
  align-items: center;
  font-size: 0.68rem;
  font-weight: 700;
  padding: 0.15rem 0.5rem;
  border-radius: 10px;
  line-height: 1.2;
  white-space: nowrap;
  margin: 0 0.4rem;
}

/* 課程類型樣式 */
.badge-pill.type-private { background: #fef3c7; color: #92400e; }
.badge-pill.type-group   { background: #dbeafe; color: #1e40af; }

/* 後端控制的通用狀態樣式 */
.badge-pill.status-success { background: #dcfce7; color: #15803d; }     /* booked / PENDING */
.badge-pill.status-danger  { background: #fee2e2; color: #991b1b; }     /* cancelled / CANCELLED */
.badge-pill.status-neutral { background: #f3f4f6; color: #4b5563; }     /* expired / EXPIRED */
.badge-pill.status-info    { background: #e0f2fe; color: #0369a1; }     /* completed / COMPLETED */
.badge-pill.status-warning { background: #fef9c3; color: #854d0e; }     /* cancel-pending / CANCEL_PENDING */
</style>
