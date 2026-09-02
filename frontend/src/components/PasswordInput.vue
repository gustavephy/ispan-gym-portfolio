<script setup>
/**
 * 密碼輸入框：右側附「顯示／隱藏」眼睛按鈕。
 *
 * <p>只包住 <input> 本身，外層 .form-group、label、密碼規則清單、錯誤訊息都留在呼叫端。
 * id／required／autocomplete／:disabled／動態 class／@keyup.enter 等一律透過 $attrs
 * 原封轉發到內層 input（inheritAttrs 關掉），所以 <label for> 關聯與既有的
 * page.locator('#id') 定位都不受影響。
 *
 * <p>每個實例各自維護 revealed，同一頁的「密碼」與「確認密碼」不會連動。
 * 切換只改 input 的 type，不碰值、游標或驗證狀態；按鈕固定 type="button"，
 * 不會觸發表單送出。
 */
import { ref } from 'vue'

defineOptions({ inheritAttrs: false })

const model = defineModel({ type: String, default: '' })

const revealed = ref(false)

function toggle() {
  revealed.value = !revealed.value
}
</script>

<template>
  <div class="pw-input">
    <input
      v-bind="$attrs"
      v-model="model"
      :type="revealed ? 'text' : 'password'"
      class="form-input pw-has-toggle"
    />
    <button
      type="button"
      class="pw-input__toggle"
      :aria-label="revealed ? '隱藏密碼' : '顯示密碼'"
      :disabled="$attrs.disabled === true || $attrs.disabled === ''"
      @mousedown.prevent
      @click="toggle"
    >
      <!-- 密碼顯示中：閉眼加睫毛，表示可按下重新隱藏。 -->
      <svg
        v-if="revealed"
        xmlns="http://www.w3.org/2000/svg"
        width="18"
        height="18"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M3.5 9.5c2.2 3.3 5.1 4.9 8.5 4.9s6.3-1.6 8.5-4.9" />
        <path d="m5.6 12.1-1.7 2M9.2 14l-.7 2.5m6.3-2.5.7 2.5m2.9-4.4 1.7 2" />
      </svg>
      <!-- 密碼隱藏中：張眼加睫毛，表示可按下查看。 -->
      <svg
        v-else
        xmlns="http://www.w3.org/2000/svg"
        width="18"
        height="18"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M2.5 12s3.5-5 9.5-5 9.5 5 9.5 5-3.5 5-9.5 5S2.5 12 2.5 12Z" />
        <circle cx="12" cy="12" r="2.75" />
        <path d="m5.2 8.5-1.5-2M9 7.2l-.6-2.4M15 7.2l.6-2.4m3.2 3.7 1.5-2" />
      </svg>
    </button>
  </div>
</template>

<style scoped>
.pw-input {
  position: relative;
  width: 100%;
}

/* 眼睛按鈕壓在 input 右緣；以自身中心對齊，兼容不同頁面的 input 高度。 */
.pw-input__toggle {
  position: absolute;
  top: 50%;
  right: 0;
  transform: translateY(-50%);
  height: 2.75rem;
  width: 2.75rem;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--text-muted, #94a3b8);
  cursor: pointer;
  transition: color 0.2s ease;
}

.pw-input__toggle:hover:not(:disabled) {
  color: var(--accent, #c99a5b);
}

.pw-input__toggle:focus-visible {
  outline: 2px solid var(--accent, #c99a5b);
  outline-offset: -2px;
  border-radius: var(--radius-sm, 8px);
}

.pw-input__toggle:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
</style>
