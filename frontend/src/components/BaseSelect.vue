<script setup>
const model = defineModel()

defineProps({
  options: {
    type: Array,
    required: true
    // Supports [{ label: 'Option 1', value: 'val1' }] or ['Option 1', 'Option 2']
  },
  placeholder: {
    type: String,
    default: ''
  },
  disabled: {
    type: Boolean,
    default: false
  }
})
</script>

<template>
  <div class="custom-select-container" :class="{ 'is-disabled': disabled }">
    <select v-model="model" class="custom-select-element" :disabled="disabled">
      <option v-if="placeholder" value="" disabled selected>{{ placeholder }}</option>
      <option 
        v-for="opt in options" 
        :key="typeof opt === 'object' ? opt.value : opt" 
        :value="typeof opt === 'object' ? opt.value : opt"
      >
        {{ typeof opt === 'object' ? opt.label : opt }}
      </option>
    </select>
    <div class="select-arrow-icon">
      <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"></polyline></svg>
    </div>
  </div>
</template>

<style scoped>
.custom-select-container {
  position: relative;
  display: inline-block;
  width: 100%;
}

.custom-select-element {
  width: 100%;
  height: 40px;
  padding: 0.5rem 2.25rem 0.5rem 0.88rem;
  border-radius: 8px;
  border: 1px solid var(--border, #e2e8f0);
  font-size: 0.875rem;
  font-weight: 500;
  background-color: var(--bg-soft, #f8fafc);
  color: var(--text-main, #1e293b);
  outline: none;
  font-family: inherit;
  cursor: pointer;
  appearance: none;
  -webkit-appearance: none;
  -moz-appearance: none;
  transition: all 0.2s ease;
  box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.02);
}

.custom-select-element:hover:not(:disabled) {
  border-color: var(--primary, #3b82f6);
  background-color: var(--bg-card, #ffffff);
}

.custom-select-element:focus:not(:disabled) {
  border-color: var(--primary, #3b82f6);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
  background-color: var(--bg-card, #ffffff);
}

.select-arrow-icon {
  position: absolute;
  right: 0.88rem;
  top: 50%;
  transform: translateY(-50%);
  color: var(--text-muted, #94a3b8);
  pointer-events: none;
  display: flex;
  align-items: center;
  transition: color 0.2s ease;
}

.custom-select-element:focus ~ .select-arrow-icon {
  color: var(--primary, #3b82f6);
}

.is-disabled .custom-select-element {
  cursor: not-allowed;
  opacity: 0.6;
  background-color: var(--bg-disabled, #f1f5f9);
}
</style>
