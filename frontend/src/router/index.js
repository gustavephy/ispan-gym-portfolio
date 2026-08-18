import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth.js'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    //會員/公開區域
    {
      path: '/',
      component: () => import('@/layouts/DefaultLayout.vue'),
      children: [
        { path: '', component: () => import('@/views/home/HomeView.vue') },
        { path: 'courses', component: () => import('@/views/course/CourseView.vue') },
        { path: 'reservation', component: () => import('@/views/reservation/ReservationView.vue') },
        { path: 'group-classes', component: () => import('@/views/reservation/GroupClassView.vue') },
        { path: 'products', component: () => import('@/views/product/ProductView.vue') },
        { path: 'products/:id', component: () => import('@/views/product/ProductDetail.vue') },
        {
          path: 'subscription',
          component: () => import('@/views/subscription/SubscriptionView.vue'),
        },
        { path: 'cart', component: () => import('@/views/cart/CartView.vue') },
        { path: 'checkout', component: () => import('@/views/cart/CheckoutView.vue') },
        { path: 'orders', component: () => import('@/views/member/OrderListView.vue') },
        {
          path: 'trainer/schedules',
          component: () => import('@/views/trainer/TrainerScheduleView.vue'),
        },
        {
          path: 'admin/group-courses',
          component: () => import('@/views/admin/AdminGroupCourseView.vue'),
        },
        {
          path: 'admin/group-classes',
          component: () => import('@/views/admin/AdminGroupClassSessionView.vue'),
        },
      ],
    },
    // 具名路由：401 攔截器會導向這裡，並以 query.redirect 記住原本要去的頁面
    {
      path:'/login',
      name:'login',
      component:()=>import('@/views/account/LoginView.vue'),
      // 已登入者不需要看到登入頁：導向原目標（redirect query）或首頁。
      // token 過期的情況不在這裡驗證——之後任何 API 回 401 時，
      // 攔截器會清除登入狀態並導回登入頁，屆時本守衛自然放行。
      beforeEnter: (to) => {
        const auth = useAuthStore()
        if (auth.isLoggedIn) {
          return typeof to.query.redirect === 'string' ? to.query.redirect : '/'
        }
      }
    }
  ],
})

export default router
