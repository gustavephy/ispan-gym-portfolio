<script setup>
import '@/assets/card.css'
import { formatImageUrl } from '@/utils/image.js';
import { useCartStore } from '@/stores/cart.js';
import { useAuthStore } from '@/stores/auth.js';
import { useFeedback } from '@/composables/useFeedback.js';
import FeedbackToast from '@/components/FeedbackToast.vue';
import { useRouter } from 'vue-router';

const props = defineProps({
  product: { type: Object, required: true }
})

const emit = defineEmits(['add-to-cart'])

const cartStore = useCartStore();
const authStore = useAuthStore();
const router = useRouter();
const { feedback, showFeedback } = useFeedback(2000);

// 格式化價格為千分位字串 (例如: 10,000)
const formatPrice = (price) => Number(price).toLocaleString('zh-TW')

const addToCart = async () => {
  if (!authStore.isLoggedIn) {
    localStorage.setItem('pending_add_to_cart_product_id', props.product.productId)
    router.push({ name: 'login', query: { redirect: '/products' } })
    return
  }
  try {
    const r = await cartStore.addItem(props.product.productId, 1)
    if (r.success) {
      showFeedback('已加入購物車！', 'success')
    } else {
      showFeedback(r.message || '加入購物車失敗', 'error')
    }
  } catch (err) {
    showFeedback(err.response?.data?.message || '加入購物車失敗', 'error')
  }
}

</script>

<template>
    <div class="item-card" @click="$router.push(`/products/${product.productId}`)">
        <div class="item-cover" :style="!product.imageUrl ? {background: 'linear-gradient(135deg, #fce4d6, #f9d5c5)' }: {} ">
            <img v-if = "product.imageUrl" :src="formatImageUrl(product.imageUrl)" class="cover-image" alt="商品封面">
            <span class="type-badge badge-product">商品</span>
        </div>

        <div class="item-info">
            <h3 class="item-name">{{ product.name }}</h3>
            <p class="item-desc">{{ product.description || '點擊查看詳情'}}</p>

            <div class="item-footer">
                <div>
                    <span class="price">NT$ {{ formatPrice(product.price) }}</span>
                    <span v-if = "product.stock <= 5 && product.stock > 0" class="stock-warn" >僅剩 {{ product.stock }}</span>
                    <span v-if = "product.stock ==0" class="stock-empty" >已售完</span>
                </div>
                <button 
                class="action-btn" 
                :disabled="product.stock == 0"
                @click.stop="addToCart">
                    <i class="fas fa-shopping-cart"></i>
                    {{ product.stock === 0 ? '售完' : '加入購物車' }}
                </button>
            </div>
        </div>
    </div>
    <FeedbackToast :feedback="feedback" />
</template>

<style scoped>

</style>