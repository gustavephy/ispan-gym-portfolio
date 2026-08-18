import { createRouter, createWebHistory } from 'vue-router'

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
  ],
})

export default router
