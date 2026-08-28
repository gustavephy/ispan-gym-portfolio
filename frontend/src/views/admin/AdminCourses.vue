<template>
  <div class="page-container admin-dashboard">
    <span class="section-tag">ADMIN</span>
    <h1 class="section-title">課程方案管理</h1>
    <p class="section-desc">管理團體課程主檔與私教課程方案，包含新增、編輯、上下架操作。</p>

    <!-- ERP 頂部標籤頁 -->
    <div class="erp-tabs" style="display: flex; gap: 1.5rem; border-bottom: 1.5px solid var(--border, #e2e8f0); margin-bottom: 2rem;">
      <button class="tab-btn" :class="{ active: currentTab === 'groupCourses' }" @click="currentTab = 'groupCourses'">
        團體課程管理
      </button>
      <button class="tab-btn" :class="{ active: currentTab === 'privateCourses' }" @click="currentTab = 'privateCourses'">
        私教課程管理
      </button>
    </div>

    <!-- 載入對應頁籤元件 -->
    <AdminGroupCourses
      v-if="currentTab === 'groupCourses'"
      :categories="courseCategories"
      :trainersList="trainersList"
      @openCategoryManager="showCategoryManager = true"
    />
    <AdminPrivateCourses
      v-else-if="currentTab === 'privateCourses'"
      :categories="courseCategories"
      :trainersList="trainersList"
    />

    <!-- 分類管理 Modal -->
    <CategoryManagerModal
      v-model:visible="showCategoryManager"
      :categories="courseCategories"
      @reload="loadCategories"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import AdminGroupCourses from '@/components/admin/AdminGroupCourses.vue'
import AdminPrivateCourses from '@/components/admin/AdminPrivateCourses.vue'
import CategoryManagerModal from '@/components/admin/CategoryManagerModal.vue'
import api from '@/api/axios.instance.js'

const currentTab = ref('groupCourses')
const showCategoryManager = ref(false)
const courseCategories = ref([])
const trainersList = ref([])

async function loadCategories() {
  try {
    const res = await api.get('/api/course-categories')
    courseCategories.value = (res.data || []).map(cat => ({
      id: cat.courseCategoryId,
      name: cat.name
    }))
  } catch (err) {
    console.error('Failed to load categories:', err)
  }
}

async function loadTrainers() {
  try {
    const res = await api.get('/api/trainers')
    trainersList.value = res.data || []
  } catch (err) {
    console.error('Failed to load trainers:', err)
  }
}

onMounted(() => {
  loadCategories()
  loadTrainers()
})
</script>

<style scoped>
.erp-tabs .tab-btn {
  background: none;
  border: none;
  padding: 0.75rem 1.5rem;
  font-size: 0.95rem;
  font-weight: 700;
  color: var(--text-secondary, #64748b);
  cursor: pointer;
  border-bottom: 3px solid transparent;
  transition: all 0.25s ease;
}
.erp-tabs .tab-btn:hover {
  color: var(--primary, #5a4a42);
}
.erp-tabs .tab-btn.active {
  color: var(--primary, #5a4a42);
  border-bottom-color: var(--primary, #5a4a42);
}
</style>