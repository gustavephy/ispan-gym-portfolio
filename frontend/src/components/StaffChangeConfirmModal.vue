<script setup>
import AppModal from '@/components/AppModal.vue'
import OperationMessage from '@/components/OperationMessage.vue'

/**
 * 人員資料修改的二次確認彈窗。四個入口共用同一套互動：
 *   - Employee 自助電話（EmployeeProfileView）
 *   - Trainer 自助專業資料（TrainerProfileView）
 *   - Admin 代改員工個資（AdminEmployeeView）
 *   - Admin 代改教練專業資料（AdminTrainerView）
 *
 * 呼叫端負責：送出前先驗證、算出實際變更欄位（沒有變更就不要打開這個彈窗、不送 API）、
 * 在 @confirm 事件裡實際打 API。彈窗只呈現「修改前 → 修改後」對照與送出/返回按鈕，
 * 不持有表單狀態、不打 API。彈窗顯示的 changes 必須與呼叫端即將送出的 payload 同源。
 */

defineProps({
  /** v-model:visible，比照 AppModal */
  title: { type: String, default: '確認修改' },
  intro: { type: String, default: '請確認以下變更，送出後即會更新資料。' },
  /** [{ key, label, before, after }]；before／after 為顯示字串，空值會顯示「未設定」 */
  changes: { type: Array, default: () => [] },
  /** 送出中：鎖住兩顆按鈕與遮罩關閉 */
  saving: { type: Boolean, default: false },
  /** 就地錯誤訊息（例如送出後 API 回業務錯誤，彈窗不關、讓使用者返回修改） */
  errorMessage: { type: String, default: '' },
  confirmLabel: { type: String, default: '確認修改' },
})

const visible = defineModel('visible', { type: Boolean, default: false })
const emit = defineEmits(['confirm'])

function display(value) {
  const text = value == null ? '' : String(value).trim()
  return text === '' ? '未設定' : text
}
</script>

<template>
  <AppModal
    v-model:visible="visible"
    :title="title"
    size="md"
    :close-on-overlay="!saving"
  >
    <p class="confirm-intro">{{ intro }}</p>

    <dl class="change-list">
      <div v-for="change in changes" :key="change.key ?? change.label" class="change-item">
        <dt>{{ change.label }}</dt>
        <dd>
          <div class="change-value">
            <span class="change-value__label">修改前</span>
            <p>{{ display(change.before) }}</p>
          </div>
          <span class="change-arrow" aria-hidden="true">→</span>
          <div class="change-value">
            <span class="change-value__label">修改後</span>
            <p>{{ display(change.after) }}</p>
          </div>
        </dd>
      </div>
    </dl>

    <OperationMessage :message="errorMessage" tone="error" />

    <template #footer>
      <button type="button" class="btn btn-ghost" :disabled="saving" @click="visible = false">
        返回編輯
      </button>
      <button type="button" class="btn btn-primary" :disabled="saving || changes.length === 0" @click="emit('confirm')">
        {{ saving ? '送出中…' : confirmLabel }}
      </button>
    </template>
  </AppModal>
</template>

<style scoped>
.confirm-intro {
  margin: 0 0 1rem;
  color: var(--text-secondary);
}

.change-list {
  display: flex;
  flex-direction: column;
  gap: 0.9rem;
  margin: 0;
}

.change-item {
  padding: 0.9rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-section);
}

.change-item dt {
  margin-bottom: 0.55rem;
  font-weight: 700;
  color: var(--text-primary);
}

.change-item dd {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: start;
  gap: 0.75rem;
  margin: 0;
}

.change-value__label {
  font-size: 0.75rem;
  font-weight: 700;
  color: var(--text-muted);
}

.change-value p {
  margin: 0.2rem 0 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.change-arrow {
  padding-top: 1.35rem;
  color: var(--text-muted);
}

@media (max-width: 560px) {
  .change-item dd {
    grid-template-columns: 1fr;
  }

  .change-arrow {
    padding: 0;
    transform: rotate(90deg);
    justify-self: center;
  }
}
</style>
