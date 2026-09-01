<script setup>
/**
 * 人員管理頁共用的狀態色塊。把 Employee 狀態／角色與 Trainer 狀態／停用的
 * 「值 → 中文 → 色調」對照集中在這一個檔案，管理端各頁不再各自維護 STATUS_MAP。
 *
 * kind：
 *   - 'employeeStatus'  對應後端 EmployeeStatus（PENDING_APPROVAL/ACTIVE/REJECTED/INACTIVE）
 *   - 'employeeRole'    對應後端 EmployeeRole（EMPLOYEE/ADMIN）
 *   - 'trainerStatus'   對應後端 TrainerStatus（ACTIVE/UNAVAILABLE）＋額外的 'DELETED'
 *                       （教練已軟刪除；deletedAt != null，非 TrainerStatus 的值）
 *
 * 對照不到的值不會壞：直接顯示原字串、用中性色。
 */
const props = defineProps({
  kind: {
    type: String,
    required: true,
    validator: (v) => ['employeeStatus', 'employeeRole', 'trainerStatus'].includes(v),
  },
  value: { type: String, default: '' },
})

// tone 值對應下方 scoped style 的 .badge--<tone>
const MAPS = {
  employeeStatus: {
    PENDING_APPROVAL: { label: '待審核', tone: 'warning' },
    ACTIVE: { label: '在職', tone: 'success' },
    REJECTED: { label: '已拒絕', tone: 'danger' },
    INACTIVE: { label: '已停用', tone: 'neutral' },
  },
  employeeRole: {
    EMPLOYEE: { label: '一般員工', tone: 'info' },
    ADMIN: { label: '管理員', tone: 'accent' },
  },
  trainerStatus: {
    ACTIVE: { label: '可服務', tone: 'success' },
    UNAVAILABLE: { label: '暫停服務', tone: 'warning' },
    DELETED: { label: '已停用', tone: 'neutral' },
  },
}

const resolved = () => MAPS[props.kind]?.[props.value] ?? { label: props.value || '—', tone: 'neutral' }
</script>

<template>
  <span class="badge" :class="`badge--${resolved().tone}`">{{ resolved().label }}</span>
</template>

<style scoped>
.badge {
  display: inline-block;
  padding: 0.25rem 0.75rem;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 700;
  white-space: nowrap;
  line-height: 1.4;
}

.badge--success {
  background: #e3f4e6;
  color: #256b33;
}
.badge--warning {
  background: #fbf1d9;
  color: #8a6410;
}
.badge--danger {
  background: #fae3e0;
  color: #9b2c1e;
}
.badge--neutral {
  background: #edeae5;
  color: var(--text-secondary);
}
.badge--info {
  background: #e3ecf5;
  color: #38618c;
}
.badge--accent {
  background: #f3e6d2;
  color: #8a5a1e;
}
</style>
