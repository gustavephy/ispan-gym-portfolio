<script setup>
    import { ref }from vue;
    import { useProductStore } from '@/stores/products.js'
    function getCourseImage(course){
        return course.ImageUrl ? formatImageUrl(course.imageUrl) : defaultCourseImg
    }
</script>
    
<template>
    <section class="section bg-white" id="courses">
        <div class="page-container">
            <span class="section-tag">COURSES</span>
            <h2 class="section-title">多元課程方案</h2>
            <p class="section-desc">我們提供完整的課程體系與專業認證教練，陪伴您達成每一個階段的健身目標。</p>
        </div>
        <div v-if="coursesLoading" class="spinner"></div>
        <div v-else-if="trainerCourses.length === 0" class="empty-state">
            <p>目前尚無課程資料，請確認後端服務已啟動。</p>
        </div>
        <div class="courses-grid" v-else>
            <RouterLink
                v-for="c in trainerCourses.slice(0,8)"
                :key="c.id"
                :to="`/reservations?courseId=${c.id}`"
                class="course-cord-clickable"
            >
            <div class="course-image-container">
                <img :src="getCourseImage(c)" :alt="c.name" class="course-coach-img"/>
            </div>
            </RouterLink>
        </div>

    </section>
</template>

<style scoped>
    
</style>