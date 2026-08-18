import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    //會員/公開區域
    {
      path:'/',
      component:()=>import("@/layouts/DefaultLayout.vue"),
      children:[
        {path:'',component:()=>import('@/views/home/HomeView.vue')},
        {path:'courses',component:()=>import('@/views/course/CourseView.vue')},
        {path:'reservation',component:()=>import('@/views/reservation/ReservationView.vue')},
        {path:'products',component:()=>import('@/views/product/ProductView.vue')},
        {path:'products/:id',component:()=>import('@/views/product/ProductDetail.vue')},
        {path:'subscription',component:()=>import('@/views/subscription/SubscriptionView.vue')},
        {path:'cart',component:()=>import('@/views/cart/CartView.vue')},
        {path:'checkout',component:()=>import('@/views/cart/CheckoutView.vue')},
        {path:'orders',component:()=>import('@/views/member/OrderListView.vue')}
      ]
    }
  ],
})

export default router
