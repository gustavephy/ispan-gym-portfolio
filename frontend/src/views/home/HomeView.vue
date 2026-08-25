<template>
  <div class="home-page">
    <!-- Hero Banner (首頁主視覺區) -->
    <section class="hero">
      <!-- 背景半透明遮罩，提升上方文字易讀性 -->
      <div class="hero-overlay"></div>
      <div class="hero-content">
        <p class="hero-tag">台灣 No.1 健身管理平台</p>
        <h1 class="hero-title">打造屬於你的<br><span class="hero-highlight">健康生活方式</span></h1>
        <p class="hero-desc">專業教練一對一指導・智慧課程管理・精選健身補給品<br>從今天開始，讓改變成為習慣。</p>
        <div class="hero-actions">
          <RouterLink to="/reservation" class="btn btn-accent hero-btn">立即預約課程</RouterLink>
          <RouterLink to="/courses" class="btn btn-outline-white hero-btn">瀏覽課程方案</RouterLink>
        </div>
        <!-- 數據統計看板：展示健身房特色數據，樣式定義在 home.css (.hero-stats 等) -->
        <div class="hero-stats">
          <div class="stat"><span class="stat-n">50+</span><span class="stat-l">專業教練</span></div>
          <div class="stat-divider"></div>
          <div class="stat"><span class="stat-n">3,000+</span><span class="stat-l">活躍學員</span></div>
          <div class="stat-divider"></div>
          <div class="stat"><span class="stat-n">98%</span><span class="stat-l">學員滿意度</span></div>
        </div>
      </div>
    </section>

    <!-- 三大特色 -->
    <section class="features-section">
      <div class="page-container">
        <div class="features-grid">
          <div class="feature-card" v-for="f in features" :key="f.title">
            <h3>{{ f.title }}</h3>
            <p>{{ f.desc }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- 課程與方案 -->
    <HomeCourses />

    <!-- 健身商城精選 -->
    <HomeProductsFeatured />

  </div>
</template>

<script setup>
import { onMounted, inject, provide } from 'vue'
import { RouterLink } from 'vue-router'

import HomeProductsFeatured from './components/HomeProductsFeatured.vue'
import HomeCourses from './components/HomeCourses.vue'

// 嘗試從上層 (DefaultLayout) 取得 showToast，若無則提供空函式避免子元件 inject 失敗
const parentShowToast = inject('showToast', null)
provide('showToast', parentShowToast ?? (() => {}))

const features = [
  { title: '頂級健身設備', desc: '引進國際大廠器材，並設有專屬自由重量區與多功能訓練草皮區。' },
  { title: '智慧課程管理', desc: '線上排班、一鍵預約、無感金鑰核銷核課，上課體驗流暢無阻礙。' },
  { title: '專屬補給商城', desc: '為會員精選乳清蛋白、健康零食與防護裝備，提供便捷的線上與門市購買管道。' }
]

onMounted(() => {
})
</script>

<style src="./home.css"></style>
