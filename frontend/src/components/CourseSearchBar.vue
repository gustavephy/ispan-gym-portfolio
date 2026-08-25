<script setup>
import { ref, watch, onMounted } from 'vue'
import { storeToRefs } from 'pinia';
import api from '@/api/axios.instance.js'
import { useCoursesStore } from '@/stores/courses';

const props = defineProps({
    showKeyword: {
        type: Boolean,
        default: false,
    },
    showDate: {
        type: Boolean,
        default: false,
    },
    showTrainer: {
        type: Boolean,
        default: false,
    },
    showCategory: {
        type: Boolean,
        default: false,
    },
    showRoom: {
        type: Boolean,
        default: false,
    },
    showType: {
        type: Boolean,
        default: false,
    },
    rooms: {
        type: Array,
        default: () => [],
    },
    showExpiryBefore: {
        type: Boolean,
        default: false,
    },
    modelValue: {
        type: Object,
        default: () => ({}),
    },
})

const emit = defineEmits(['search', 'update:modelValue'])

//定義課程類型
const typeOptions = [
    {value: '', label:'全部'},
    {value:'PRIVATE', label:'私教課'},
    {value:'GROUP', label:'團體課'},
]

const coursesStore = useCoursesStore()
const { categories: categoryOptions } = storeToRefs(coursesStore)
const trainerOptions = ref([])
const local = ref({
    keyword: '',
    date: '',
    trainerId: '',
    categoryId: '',
    roomId: '',
    type: '',
    expiryBefore: '',
    ...props.modelValue,
})

function emitSearch() {
    const val = {...local.value}
    emit('search', val)
    emit('update:modelValue', val)
}

//類型
function setType(v){
    local.value.type = v;
    emitSearch()
}

async function loadCategories(){
    if(categoryOptions.value.length === 0){
        await coursesStore.loadCategories()
    }
}

async function loadTrainers(){
    const res = await api.get('/api/trainers')
    trainerOptions.value = res.data
}

watch(() => props.modelValue, (v) => {
  if (v) local.value = { ...local.value, ...v }
}, { deep: true })

onMounted(()=>{
  if(props.showCategory) loadCategories()
  if(props.showTrainer) loadTrainers()
})

</script>
<template>
    <div class="csb-wrap">
        <!-- 關鍵字搜尋 -->
        <div v-if="showKeyword" class="csb-field csb-field--grow">
            <label class="csb-label">關鍵字搜尋</label>
            <input 
                v-model="local.keyword" 
                class="csb-input" 
                type="text" 
                placeholder="輸入課程名稱..."
                @input="emitSearch"
            />
        </div>

        <!-- 課程型態 -->
        <div v-if="showType" class="csb-field csb-field--tabs">
            <label class="csb-label">課程型態</label>
            <div class="csb-tabs">
                <button 
                    v-for="opt in typeOptions" 
                    :key="opt.value" 
                    class="csb-tab" 
                    :class="{ active: local.type === opt.value }"
                    type="button"
                    @click="setType(opt.value)"
                >
                    {{ opt.label }}
                </button>
            </div>
        </div>

        <!-- 課程種類 / 分類 -->
        <div v-if="showCategory" class="csb-field">
            <label class="csb-label">課程種類</label>
            <select v-model="local.categoryId" class="csb-select" @change="emitSearch">
                <option value="">全部種類</option>
                <option v-for="c in categoryOptions" :key="c.id" :value="c.id">
                    {{ c.name }}
                </option>
            </select>
        </div>

        <!-- 指導教練 -->
        <div v-if="showTrainer" class="csb-field">
            <label class="csb-label">指導教練</label>
            <select v-model="local.trainerId" class="csb-select" @change="emitSearch">
                <option value="">全部教練</option>
                <option v-for="t in trainerOptions" :key="t.trainerId" :value="t.trainerId">
                    {{ t.displayName }}
                </option>
            </select>
        </div>

        <!-- 教室選擇 -->
        <div v-if="showRoom" class="csb-field">
            <label class="csb-label">教室選擇</label>
            <select v-model="local.roomId" class="csb-select" @change="emitSearch">
                <option value="">全部教室</option>
                <option v-for="r in rooms" :key="r.id || r.roomId" :value="r.id || r.roomId">
                    {{ r.name }}
                </option>
            </select>
        </div>

        <!-- 日期選擇 -->
        <div v-if="showDate" class="csb-field">
            <label class="csb-label">日期</label>
            <input 
                v-model="local.date" 
                class="csb-input" 
                type="date" 
                @change="emitSearch"
            />
        </div>

        <!-- 效期截止前選擇 -->
        <div v-if="showExpiryBefore" class="csb-field">
            <label class="csb-label">效期截止前</label>
            <input 
                v-model="local.expiryBefore" 
                class="csb-input" 
                type="date" 
                @change="emitSearch"
            />
        </div>
    </div>
</template>

<style scoped>
.csb-wrap {
  display: flex;
  align-items: flex-end;
  gap: 1rem;
  flex-wrap: wrap;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 1.25rem 1.75rem;
  margin-bottom: 2rem;
  box-shadow: var(--shadow-sm);
}
.csb-field {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  min-width: 140px;
  flex-shrink: 0;
}
.csb-field--grow {
  flex: 1;
  min-width: 180px;
}
.csb-field--tabs {
  min-width: unset;
}
.csb-label {
  font-size: 0.72rem;
  font-weight: 800;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  white-space: nowrap;
}
.csb-input,
.csb-select {
  height: 38px;
  padding: 0 0.85rem;
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  background: var(--bg-soft);
  color: var(--text-primary);
  font-size: 0.85rem;
  font-weight: 600;
  outline: none;
  cursor: pointer;
  transition: border-color 0.2s;
  width: 100%;
  box-sizing: border-box;
}
.csb-input:focus,
.csb-select:focus {
  border-color: var(--primary);
}
.csb-tabs {
  display: flex;
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 2px;
  height: 38px;
  align-items: center;
}
.csb-tab {
  background: transparent;
  border: none;
  color: var(--text-muted);
  padding: 0 1rem;
  font-size: 0.85rem;
  font-weight: 700;
  height: 100%;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
}
.csb-tab.active {
  background: var(--primary);
  color: #fff;
}
.csb-reset {
  height: 38px;
  padding: 0 1rem;
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  background: var(--bg-soft);
  color: var(--text-muted);
  font-size: 0.8rem;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
  align-self: flex-end;
  flex-shrink: 0;
}
.csb-reset:hover {
  border-color: #ef4444;
  color: #ef4444;
  background: #fff5f5;
}
@media (max-width: 768px) {
  .csb-wrap { padding: 1rem; gap: 0.75rem; }
  .csb-field { min-width: 130px; }
}
</style>