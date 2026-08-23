<script setup>
    defineProps({
        modelValue: {
            type: String,
            required: true
        },
        label: {
            type: String,
            default: '快速商品分類'
        },
        options: {
            type: Array,
            required: true
        }
    })
    defineEmits (['update:modelValue'])
    
</script>

<template>
    <div class="table-filter-bar">
        <span class="filter-bar-label">{{ label }}</span>
        <div class="filter-buttons">
            <button v-for="opt in options" :key="opt.value"
            :class="['filter-tab-sm', { active: modelValue === opt.value}]">
                {{ opt.label }}
            </button>
        </div>
    </div>
</template>

<style scoped>
.table-filter-bar {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 1.5rem;
  padding: 1rem 1.5rem;
  border-radius: 12px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
}
.filter-bar-label {
  font-size: 0.85rem;
  font-weight: 700;
  color: #475569;
  white-space: nowrap;
}
.filter-buttons {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}
.filter-tab-sm {
  padding: 0.4rem 1rem;
  font-size: 0.8rem;
  font-weight: 600;
  border-radius: 20px;
  border: 1px solid #cbd5e1;
  background: #ffffff;
  color: #475569;
  cursor: pointer;
  transition: all 0.2s ease;
}
.filter-tab-sm:hover {
  background: var(--bg-soft);
  color: var(--primary);
}
.filter-tab-sm.active {
  background: var(--primary);
  color: #ffffff;
  border-color: transparent;
}
</style>