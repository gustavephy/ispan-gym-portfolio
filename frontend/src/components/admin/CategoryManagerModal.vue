<template>
  <!-- AppModal 的 model 叫 visible（defineModel('visible')），綁 model-value 不會有任何反應、彈窗永遠打不開 -->
  <AppModal :visible="visible" @update:visible="val => $emit('update:visible', val)" title="管理課程分類" size="md">
    <div class="category-manager-content">
      <!-- 新增分類表單 -->
      <form @submit.prevent="createCategory" class="card" style="padding: 1rem; margin-bottom: 1.5rem; background: var(--bg-soft, #f8fafc); border: 1px solid var(--border, #e2e8f0); border-radius: 8px;">
        <h5 style="margin-bottom: 0.75rem; font-weight: 700; font-size: 0.9rem; margin-top: 0;">新增分類</h5>
        <div style="display: flex; gap: 0.5rem; flex-wrap: wrap;">
          <input v-model="newCategoryName" class="form-input" style="flex: 2; min-width: 150px; padding: 0.4rem 0.6rem; border-radius: 6px; border: 1px solid #cbd5e1;" placeholder="分類名稱，例如：瑜珈" required />
          <button type="submit" class="btn btn-primary" style="padding: 0.5rem 1rem;">新增</button>
        </div>
      </form>

      <!-- 分類列表 -->
      <div class="card" style="padding: 1rem; max-height: 300px; overflow-y: auto; border: 1px solid var(--border, #e2e8f0); border-radius: 8px;">
        <h5 style="margin-bottom: 0.75rem; font-weight: 700; font-size: 0.9rem; margin-top: 0;">目前分類清單</h5>
        <table style="width: 100%; border-collapse: collapse;">
          <thead>
            <tr style="border-bottom: 1px solid var(--border, #cbd5e1); text-align: left; font-size: 0.8rem; color: #64748b;">
              <th style="padding: 0.5rem;">名稱</th>
              <th style="padding: 0.5rem; text-align: right;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="cat in categories" :key="cat.id" style="border-bottom: 1px solid var(--border-light, #f1f5f9); font-size: 0.85rem;">
              <td style="padding: 0.5rem; font-weight: 600; color: #334155;">{{ cat.name }}</td>
              <td style="padding: 0.5rem; text-align: right;">
                <button type="button" @click="deleteCategory(cat)" class="btn btn-danger btn-sm" style="padding: 0.25rem 0.5rem; font-size: 0.75rem;">刪除</button>
              </td>
            </tr>
            <tr v-if="categories.length === 0">
              <td colspan="2" style="text-align: center; padding: 1rem; color: var(--text-muted, #94a3b8); font-size: 0.8rem;">無分類資料</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <template #footer>
      <button type="button" class="btn btn-primary" @click="$emit('update:visible', false)">完成</button>
    </template>
  </AppModal>
</template>

<script setup>
import { ref, inject } from 'vue'
import AppModal from '@/components/AppModal.vue'
import api from '@/api/axios.instance.js'

const props = defineProps({
  visible: { type: Boolean, required: true },
  categories: { type: Array, default: () => [] }
})

const emit = defineEmits(['update:visible', 'reload'])
const showToast = inject('showToast', null)

const newCategoryName = ref('')

async function createCategory() {
  if (!newCategoryName.value.trim()) return
  try {
    await api.post('/api/admin/course-categories', { name: newCategoryName.value.trim() })
    if (showToast) showToast('課程分類新增成功')
    newCategoryName.value = ''
    emit('reload')
  } catch (e) {
    if (showToast) showToast('新增分類失敗', 'error')
  }
}

async function deleteCategory(cat) {
  if (!confirm(`確認刪除課程分類「${cat.name}」？`)) return
  try {
    await api.delete(`/api/admin/course-categories/${cat.id}`)
    if (showToast) showToast('分類已刪除')
    emit('reload')
  } catch (e) {
    if (showToast) showToast('刪除分類失敗', 'error')
  }
}
</script>
