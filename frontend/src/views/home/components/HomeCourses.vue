<script setup>
    import { ref, computed, onMounted, inject }from vue;
    import { useRouter, RouterLink } from 'vue-router';
    import { storeToRefs } from 'pinia';
    import api from '@/api/index.js';
    import axios from '@api/axios-instance.js';
    import { useUserStore } from '@/stores/user.js';
    import { useCourseStore } from '@/stores/courses.js'
    import { formatImageUrl } from '@/utils/image.js';
    import ProductCard from '@/components/ProductCard.vue';

    const user = useUserStore();
    const router = useRouter();
    const showToast = inject('showToast');
    const courseStore = useCourseStore();

    const { coursesList } = storeToRefs(courseStore);
    const coursesLoading = ref(true);
    const hasActiveSubscription = ref(false);

    const displayedCourses = computed(() => {
        const list = coursesList.value || []
        const privateCourses = list.filter(course => course.isPrivate === false);
        return coursesList.value.slice(0, 4);
    });


    function getCourseImage(course){
        return course.ImageUrl ? formatImageUrl(course.imageUrl) : defaultCourseImg
    }
</script>
    
<template>

    <!-- 課程方案 -->
    <section class="section bg-white" id="courses">
        <div class="page-container">
            <div class="section-header">
                <span class="section-tag">COURSES</span>
                <h2 class="section-title">多元課程方案</h2>
                <p class="section-desc">我們提供完整的課程體系與專業認證教練，陪伴您達成每一個階段的健身目標。</p>
            </div>
            <div v-if="coursesLoading" class="spinner"></div>
            <div v-else-if="trainerCourses.length === 0" class="empty-state">
                <p>目前尚無課程資料，請確認後端服務已啟動。</p>
            </div>
            <div class="courses-grid" v-else>
                <ProductCard
                    v-for="c in displayedCourses"
                    :key="c.id"
                    :product="c"
                    :has-active-subscription="hasActiveSubscription"
                    @add-to-cart="handleAddToCart"
                />
            </div> 
            <div class="cta-center" style="text-align: center; margin-top: 3rem;">
                <RouterLink to="/courses" class="btn btn-primary">前往健身商城瀏覽全部 →</RouterLink>
            </div>
        </div>
    </section>

    <!-- 教練陣容 -->
    <section class = "section bg-white">
        <div class="page-container">
            <div>
                <span class="section-tag">TRAINERS</span>
                <h2 class="section-title">專業教練陣容</h2>
                 <p class="section-desc">每位教練均擁有國際認證資格，擅長不同的專業領域，量身規劃最適合你的訓練計畫。</p>
            </div>
            <div v-if="trainersLoading" class="spinner"></div>
            <div v-else-if="dbtrainers.length === 0" class="empty-state">
                <p>目前尚無教練資料，請確認後端服務已啟動。</p>
            </div>

            <!-- 有資料時：渲染教練卡片 -->
            <div class="trainers-grid" v-else>
                <div class ="trainer-card" v-for="(t, idx) in dbTrainers" :key="t.name">
                <!-- 4. 有設定頭像就顯示圖片，否則顯示首字圓形占位符（搭配隨機背景色） -->
                <img v-if="t.avatarUrl" :src="t.avatarUrl" alt="Trainer Avatar" class="trainer-avatar">
                <div v-else class="trainer-avatar-lg"  :style="{ background: trainerColor(idx) }">
                    {{ t.name?.[0] }}
                </div>
                <!-- 自動判斷並補上"教練"字樣，若名稱中已包含"教練"則不重複顯示 -->
                <h3 class="trainer-name">{{ t.name + t.name.includes('教練') ? '（教練）' : '' }}</h3>
                 <p class="trainer-specialty">{{ t.specialty || '專業教練' }}</p>
            </div>   
        </div>    
</template>

<style scoped>
    
</style>