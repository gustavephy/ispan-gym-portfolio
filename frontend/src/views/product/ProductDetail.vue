<script>
import { ref, onMounted, computed, inject } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { useProductStore } from '@/stores/productStore';
import { useAuthStore } from '@/stores/authStore';
import { useCartStore } from '@/stores/cartStore';

const productStore = useProductStore();
const auth = useAuthStore();
const cart = useCartStore();
const showToast = inject('showToast');
const route = useRoute();

const product = ref(null);
const qty = ref(1);

onMounted(async () => {
    const id = route.params.id;
    if(id) {
        const result = await productStore.getProductById(id);
        product.value = result;
    }
});

async function addToCart(){
    if(!auth.isLoggedIn){ showToast('請先登入會員', 'info'); return }
    const r = await cart.addToCart(product.value.productId, qty.value);
    if(r.success){
        showToast('已加入購物車', 'success');
    }else{
        showToast(r.message || '加入失敗', 'error');
    }
}

const specificationLabels = {
    brand: '品牌',
    weight: '重量',
    origin: '產地',
    flavor: '口味',
    color: '顏色',
    material: '材質',
    size: '尺寸',
}

const allSpecifications = computed(()=>{
    if(!product.value) return {}
    const specs={}
    Object.keys(specificationLabels).forEach(key=>{
        const val = product.value[key];
        if(val !== undefined && val !== null && val !== ''){
            specs[key] = key === 'weight' ? `${val} g` : val;
        }
    })
    return specs
});
</script>
<template>
    <div class = "page-container" v-if="product">
        <div class="breadcrumb">
            <RouterLink to="/">首頁</RouterLink>
            <span>></span>
            <RouterLink
                v-if = "product.productCategoryId" :to="{ path: '/products', query: { category: product.productCategoryId}}"  
            >{{ product.productCategoryName || '商品分類' }}</RouterLink>
            <span v-if="product.productCategoryId">></span>
            <span>{{ product.productName }}</span>
        </div>
    </div>
</template>
<style scoped>
    
</style>