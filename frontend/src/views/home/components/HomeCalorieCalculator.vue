<template>
  <section class="section calculator-section">
    <div class="page-container">
      <div class="section-header">
        <span class="section-tag">FITNESS TOOL</span>
        <h2 class="section-title text-white">BMR & TDEE 智慧熱量計算機</h2>
        <p class="section-desc text-light">科學化評估每日熱量消耗，為您規劃專屬的增肌減脂營養藍圖與推薦方案。</p>
      </div>

      <div class="calc-card card">
        <div class="calc-form">
          <div class="calc-form-grid">
            <div class="form-group">
              <label class="form-label">生理性別</label>
              <div class="gender-toggle">
                <button type="button" :class="{ active: calcForm.gender === 'male' }" @click="calcForm.gender = 'male'">男生</button>
                <button type="button" :class="{ active: calcForm.gender === 'female' }" @click="calcForm.gender = 'female'">女生</button>
              </div>
            </div>
            <div class="form-group">
              <label class="form-label">年齡 (歲)</label>
              <input type="number" v-model.number="calcForm.age" class="form-input" min="1" max="100" />
            </div>
            <div class="form-group">
              <label class="form-label">身高 (cm)</label>
              <input type="number" v-model.number="calcForm.height" class="form-input" min="50" max="250" />
            </div>
            <div class="form-group">
              <label class="form-label">體重 (kg)</label>
              <input type="number" v-model.number="calcForm.weight" class="form-input" min="10" max="200" />
            </div>
          </div>

          <div class="calc-form-grid-2">
            <div class="form-group">
              <label class="form-label">日常活動量頻率</label>
              <select v-model="calcForm.activity" class="form-input">
                <option value="1.2">久坐辦公 (無規律運動)</option>
                <option value="1.375">輕度活動 (每週輕鬆運動 1-3 天)</option>
                <option value="1.55">中度活動 (每週高強度運動 3-5 天)</option>
                <option value="1.725">重度活動 (每週高強度運動 6-7 天)</option>
                <option value="1.9">極重度活動 (專業運動員/高體力職業)</option>
              </select>
            </div>

            <div class="form-group">
              <label class="form-label">健身目標</label>
              <select v-model="calcForm.goal" class="form-input">
                <option value="lose">減脂瘦身 (-300 kcal/日)</option>
                <option value="maintain">維持體態 (符合當前 TDEE)</option>
                <option value="gain">增肌塑形 (+300 kcal/日)</option>
              </select>
            </div>
          </div>

          <div class="calc-btn-wrap">
            <button type="button" class="btn btn-accent btn-calc" @click="calculateCalories">
              開始計算熱量需求
            </button>
          </div>
        </div>

        <!-- 計算結果展示 -->
        <div class="calc-results" v-if="calcResult">
          <h3 class="results-title">計算評估報告</h3>
          <div class="results-grid">
            <div class="result-box">
              <span class="result-num">{{ calcResult.bmr }}</span>
              <span class="result-label">BMR 基礎代謝率 (kcal)</span>
            </div>
            <div class="result-box highlighted">
              <span class="result-num">{{ calcResult.tdee }}</span>
              <span class="result-label">TDEE 每日總熱量消耗 (kcal)</span>
            </div>
            <div class="result-box target">
              <span class="result-num">{{ calcResult.target }}</span>
              <span class="result-label">每日目標熱量 (kcal)</span>
            </div>
          </div>
          <div class="recommendations-box">
            <h4>威力健身專屬推薦方案：</h4>
            <p class="rec-text">{{ calcResult.recommendationText }}</p>
            
            <div class="rec-details-grid">
              <!-- Recommended Product (real products from backend only) -->
              <div class="rec-item-card" v-if="recommendedProduct">
                <div class="rec-item-badge">推薦運動補給</div>
                <div class="rec-item-header">
                  <div class="rec-item-title-wrap">
                    <span class="rec-item-title">{{ recommendedProduct.name }}</span>
                    <span class="rec-item-subtitle">NT$ {{ recommendedProduct.price }}</span>
                  </div>
                </div>
                <p class="rec-item-desc">{{ recommendedProduct.description || '精選健康補給與配備。' }}</p>
                <button @click="handleRecProductAddToCart(recommendedProduct)" class="btn btn-accent btn-sm btn-rec-action">
                  直接加入購物車
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { ref, computed, onMounted, inject } from 'vue'
import { RouterLink } from 'vue-router'
import api from '@/api/axios.instance.js'
import { useAuthStore } from '@/stores/auth.js'
import { useCartStore } from '@/stores/cart.js'

const auth = useAuthStore()
const cart = useCartStore()
const showToast = inject('showToast')

// [響應式狀態] calcForm (calcform)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const calcForm = ref({
  gender: 'male',
  age: 26,
  height: 175,
  weight: 70,
  activity: '1.375',
  goal: 'maintain'
})
// [響應式狀態] calcResult (calcresult)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const calcResult = ref(null)
// [響應式狀態] featuredProducts (featuredproducts)
// 為什麼要這麼做：在架商品清單，提供頁面渲染商品列表的資料來源。
const featuredProducts = ref([])

// [功能方法] loadProducts (載入products)
// 為什麼要這麼做：向後端 API 請求最新數據，確保頁面載入或重新整理時，使用者能看到最即時且正確的資料。
async function loadProducts() {
  try {
    const res = await api.get('/api/products', { params: { page: 0, size: 4 } })
    featuredProducts.value = (res.data.content || []).map(p => ({
      ...p,
      id: p.productId || p.id
    }))
  } catch (e) {
    console.error('Failed to load products in calculator', e)
  }
}

// [功能方法] calculateCalories (計算calories)
// 為什麼要這麼做：對目前的數值（如卡路里、折抵金額、最終付費總額）進行實時數學運算呈現給用戶。
function calculateCalories() {
  const { gender, age, height, weight, activity, goal } = calcForm.value
  
  let bmr = 0
  if (gender === 'male') {
    bmr = 13.7 * weight + 5.0 * height - 6.8 * age + 66
  } else {
    bmr = 9.6 * weight + 1.8 * height - 4.7 * age + 655
  }
  
  const bmrVal = Math.round(bmr)
  const tdeeVal = Math.round(bmrVal * parseFloat(activity))
  
  let targetVal = tdeeVal
  let recommendationText = ''
  if (goal === 'lose') {
    targetVal = tdeeVal - 300
    recommendationText = '您的目標是減脂瘦身。建議除了控制熱量，並多補充分離乳清蛋白防範肌肉流失，並預約 HIIT 燃脂課程方案來最大化卡路里消耗效率！'
  } else if (goal === 'gain') {
    targetVal = tdeeVal + 300
    recommendationText = '您的目標是增肌體態塑形。建議每天達到超盈餘熱量，搭配專業一對一重量訓練課程以科學化阻力訓練刺激肌肉生長，以及選購高能量肌酸補給品。'
  } else {
    recommendationText = '您的目標是維持體態。保持熱量均衡與日常活動量是關鍵。推薦選擇瑜珈伸展課程來放鬆肌肉、提高身體活動度與心靈舒緩。'
  }
  
  calcResult.value = {
    bmr: bmrVal,
    tdee: tdeeVal,
    target: targetVal,
    recommendationText
  }
  showToast('熱量需求評估完成！')
}

// [計算屬性] recommendedProduct (recommendedproduct)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const recommendedProduct = computed(() => {
  if (!calcResult.value) return null
  const { goal } = calcForm.value
  
  if (!featuredProducts.value || featuredProducts.value.length === 0) return null

  let found = null
  if (goal === 'lose' || goal === 'gain') {
    found = featuredProducts.value.find(p => p.name && p.name.includes('乳清蛋白'))
  } else {
    found = featuredProducts.value.find(p => p.name && p.name.includes('瑜珈墊'))
  }
  if (!found) found = featuredProducts.value[0]

  return found || null
})

// [功能方法] handleRecProductAddToCart (處理recproduct新增tocart)
// 為什麼要這麼做：響應使用者的點擊、輸入或提交等操作行為，執行相對應的業務流程邏輯。
async function handleRecProductAddToCart(prod) {
  if (!auth.isLoggedIn) {
    showToast('請先登入再加入購物車', 'info')
    return
  }
  const r = await cart.addItem(prod.productId, 1)
  if (r.success) showToast('已加入購物車')
  else showToast(r.message || '加入失敗', 'error')
}

// [生命週期掛載] 組件初始化載入
// 為什麼要這麼做：在 Vue 元件完全掛載至網頁 DOM 後，立即向後端發送請求載入初始頁面資料，提供即時的視覺展現。
onMounted(() => {
  loadProducts()
})
</script>

<style src="../home.css"></style>
