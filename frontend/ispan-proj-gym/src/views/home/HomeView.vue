<script setup>
  import { ref } from 'vue';
  import { RouterLink } from 'vue-router';
  // import HomeProductFeatured from './components/HomeProductFeatured.vue';
  // import HomeCourse from './components/HomeCourse.vue';

  const features = [
    { title: '頂級健身設備', desc: '引進國際大廠器材，並設有專屬自由重量區與多功能訓練草皮區。' },
    { title: '智慧課程管理', desc: '線上排班、一鍵預約、無感金鑰核銷核課，上課體驗流暢無阻礙。' },
    { title: '專屬補給商城', desc: '為會員精選乳清蛋白、健康零食與防護裝備，提供便捷的線上與門市購買管道。' }
  ]
</script>


<template>
  <div class="home-page">
    <section class="hero">
      <div class="hero-overlay"></div>
      <div class="hero-content">
        <p class="hero-tag">台灣 No.1 健身管理平台</p>
        <h1 class="hero-title">
          打造專屬您的<br /><span class="hero-highlight">健康生活方式</span>
        </h1>
        <p class="hero-desc">
          專業教練一對一指導・智慧課程管理・精選健身補給品從今天開始，讓改變成為習慣。
        </p>
        <div class="hero-actions">
          <RouterLink to="/reservation" class="btn btn-accent hero-btn">立即預約課程</RouterLink>
          <RouterLink to="/courses" class="btn btn-accent hero-btn">瀏覽課程方案</RouterLink>
        </div>
        <div class="hero-stats">
          <div class="stat">
            <span class="stat-n">50+</span><span class="stat-l">專業教練</span>
          </div>
          <div class="stat-divider"></div>
          <div class="stat">
            <span class="stat-n">3,000+</span><span class="stat-l">活躍學員</span>
          </div>
          <div class="stat-divider"></div>
          <div class="stat">
            <span class="stat-n">98%</span><span class="stat-l">學員滿意度</span>
          </div>
        </div>
      </div>
    </section>

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
    <!-- <HomeCourses /> -->

    <!-- 健身商城精選 -->
    <!-- <HomeProductsFeatured /> -->

  </div>
</template>
<style src="./home.css"></style>
