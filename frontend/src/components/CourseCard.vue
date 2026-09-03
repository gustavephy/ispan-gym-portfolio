<script setup>
    import '@/assets/card.css'
    import { computed, inject } from 'vue'
    import { useRouter } from 'vue-router'
    import { useAuthStore } from '@/stores/auth.js'
    import { formatImageUrl, useFallbackImage, DEFAULT_COURSE_IMAGE } from '@/utils/image.js'
    import { useCoursesStore } from '@/stores/courses.js'
    import AppButton from '@/components/AppButton.vue'

    // 接收父組件傳入的資料
    const props = defineProps({
        course: Object,
        hasActiveSubscription: Boolean
    })

    const router = useRouter()
    const auth = useAuthStore()
    const coursesStore = useCoursesStore()
    const showToast = inject('showToast', () => {})

    // 格式化價格
    const formatPrice = (price) => Number(price).toLocaleString('zh-TW')

    // 判斷是否為私教課
    const isPrivate = computed(() => props.course.isPrivate || props.course.courseType === 'PRIVATE')

    // 判斷是否有購買私教課 (簡化邏輯，不使用不存在的 reservationStore)
    const hasQuota = computed(() => {
        return false
    })

    // 處理立即預約按鈕
    const coverBg = computed(() =>
    isPrivate.value
    ? 'linear-gradient(135deg, #fef3c7, #fde68a)'
    : 'linear-gradient(135deg, #d8f3dc, #b7e4c7)'
)

    const emit = defineEmits(['purchase'])

    const handlePurchase = () => {
        emit('purchase', props.course)
    }

    //點擊卡片任何地方
    function handleCardClick() {
        if (isPrivate.value) {
            if (!hasQuota.value) {
                handlePurchase()
            } else {
                router.push(`/reservation?keyword=${encodeURIComponent(props.course.name)}`)
            }
        } else {
            // 團課
            if (!props.hasActiveSubscription) {
                router.push('/subscription')
            } else {
                router.push(`/group-classes?keyword=${encodeURIComponent(props.course.name)}`)
            }
        }
    }
</script>

<template>
    <div class="item-card" @click="handleCardClick">
        <div class="item-cover" :style="!course.imageUrl ?  {background : coverBg } : {}">
            <img v-if="course.imageUrl" :src="formatImageUrl(course.imageUrl)" class="cover-image" alt="課程封面"
                 @error="useFallbackImage($event, DEFAULT_COURSE_IMAGE)">
            <span class="type-badge" :class="isPrivate ? 'badge-private' : 'badge-group'">
                {{ isPrivate ? '私教課' : '團課' }}
            </span>
        </div>

        <div class="item-info">
            <h3 class="item-name">
                {{ course.name }}
                <span class="title-trainer"> {{ course.trainer?.name || course.trainerName }}</span>
                <span v-if = "isPrivate" class="session-badge"> </span>
            </h3>
            <p class="item-desc">{{ course.description || '點擊查看詳情' }}</p>

            <div class="item-footer">
                 <!-- 團課價格標記 -->
                 <span v-if="!isPrivate && hasActiveSubscription" class="price subscribed">✓ 已訂閱・免費預約</span>
                 <span v-else-if="!isPrivate" class="price">訂閱會員後免費</span>

                 <!-- 私教課價格標記 -->
                 <span v-else-if="isPrivate && hasQuota" class="price subscribed">✓ 已購買・可預約</span>
                 <span v-else-if="isPrivate" class="price">NT$ {{ formatPrice(course.price || 0) }}</span>
            
                 <!-- 團課按鈕：已訂閱 → 立即預約 -->
                 <AppButton
                     v-if = "!isPrivate && hasActiveSubscription"
                     variant="accent"
                     class="action-btn sub-btn"
                     @click.stop="$router.push(`/group-classes?keyword=${encodeURIComponent(course.name)}`)"
                 >立即預約</AppButton>

                 <!-- 團課按鈕：未訂閱 → 前往訂閱頁 -->
                 <AppButton
                     v-else-if="!isPrivate"
                     variant="accent"
                     class="action-btn sub-btn"
                     @click.stop="$router.push('/subscription')"
                 >立即訂閱</AppButton>
                 
                 <!-- 私教課按鈕：已購買 → 立即預約 -->
                 <AppButton
                     v-else-if="isPrivate && hasQuota"
                     variant="accent"
                     class="action-btn sub-btn"
                     @click.stop="$router.push(`/reservation?keyword=${encodeURIComponent(course.name)}`)"
                 >立即預約</AppButton>
                 
                 <!-- 私教課按鈕：未購買 → 直接購買 -->
                 <AppButton
                     v-else
                     variant="accent"
                     class="action-btn"
                     @click.stop="handlePurchase"
                 >立即購買</AppButton>
            </div>
        </div>
    </div>
</template>

<style scoped>

</style>