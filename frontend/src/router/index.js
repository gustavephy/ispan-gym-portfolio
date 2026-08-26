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
        {
          path: 'reservation',
          component: () => import('@/views/reservation/ReservationView.vue'),
          meta: { requiresAuth: true },
        },
        { path: 'group-classes', component: () => import('@/views/reservation/GroupClassView.vue') },
        { path: 'products', component: () => import('@/views/product/ProductView.vue') },
        { path: 'products/:id', component: () => import('@/views/product/ProductDetail.vue') },
        {
          path: 'subscription',
          component: () => import('@/views/subscription/SubscriptionView.vue'),
        },
        {
          path: 'cart',
          component: () => import('@/views/cart/CartView.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: 'checkout',
          component: () => import('@/views/cart/CheckoutView.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: 'orders',
          component: () => import('@/views/member/OrderListView.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: 'coupons',
          component: () => import('@/views/member/MemberCouponView.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: 'member/courses',
          component: () => import('@/views/member/MemberCourseView.vue'),
          meta: { requiresAuth: true, requiresRole: 'MEMBER' },
        },
        {
          path: 'trainer/schedules',
          component: () => import('@/views/trainer/TrainerScheduleView.vue'),
          meta: { requiresAuth: true, requiresRole: 'TRAINER' },
        },
        {
          path: 'admin/courses',
          component: () => import('@/views/admin/AdminCourses.vue'),
          meta: { requiresAuth: true, requiresRole: 'ADMIN' },
        },
        {
          path: 'admin/coupons',
          component: () => import('@/views/admin/AdminCoupons.vue'),
          meta: { requiresAuth: true, requiresRole: 'ADMIN' },
        },
        {
          path: 'admin/products',
          component: () => import('@/views/admin/AdminProducts.vue'),
          meta: { requiresAuth: true, requiresRole: 'ADMIN' },
        },
        {
          path: 'admin/inventory',
          component: () => import('@/views/admin/AdminInventory.vue'),
          meta: { requiresAuth: true, requiresRole: 'ADMIN' },
        },
        {
          path: 'admin/group-classes',
          component: () => import('@/views/admin/AdminGroupClassSessionView.vue'),
          meta: { requiresAuth: true, requiresRole: 'ADMIN' },
        },
        {
          path: 'admin/members',
          component: () => import('@/views/admin/AdminMemberView.vue'),
          meta: { requiresAuth: true, requiresRole: 'ADMIN' },
        },
        {
          path: 'admin/reports',
          component: () => import('@/views/admin/AdminReportView.vue'),
          meta: { requiresAuth: true, requiresRole: 'ADMIN' },
        },
        {
          path: 'admin/refunds',
          component: () => import('@/views/admin/AdminRefundView.vue'),
          meta: { requiresAuth: true, requiresRole: 'ADMIN' },
        },
        {
          path: 'admin/orders',
          component: () => import('@/views/admin/AdminOrderView.vue'),
          meta: { requiresAuth: true, requiresRole: 'ADMIN' },
        },
        {
          path: 'admin/employee-reviews',
          component: () => import('@/views/admin/EmployeeReviewView.vue'),
          // 同儕審核：任何在職員工皆可審核，不限管理者（後端也是 hasRole('EMPLOYEE')）
          meta: { requiresAuth: true, requiresRole: 'EMPLOYEE' },
        },
        {
          path: 'health',
          component: () => import('@/views/health/HealthHubView.vue'),
          meta: { requiresAuth: true, requiresRole: 'MEMBER' },
          children: [
            { path: '', redirect: '/health/inbody' },
            { path: 'inbody', component: () => import('@/views/health/InbodyPanel.vue') },
            { path: 'ai-plan', component: () => import('@/views/health/AiPlanPanel.vue') },
            { path: 'chat', component: () => import('@/views/health/ChatPanel.vue') },
          ],
        },
        {
          path: 'employee/chat',
          component: () => import('@/views/employee/EmployeeChatView.vue'),
          meta: { requiresAuth: true, requiresRole: 'EMPLOYEE' },
        },
      ],
    },
    // 具名路由：401 攔截器會導向這裡，並以 query.redirect 記住原本要去的頁面
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/account/LoginView.vue'),
      // 已登入者不需要看到登入頁：導向原目標（redirect query）或首頁。
      // token 過期的情況不在這裡驗證——之後任何 API 回 401 時，
      // 攔截器會清除登入狀態並導回登入頁，屆時本守衛自然放行。
      beforeEnter: (to) => {
        const auth = useAuthStore()
        if (auth.isLoggedIn) {
          return typeof to.query.redirect === 'string' ? to.query.redirect : '/'
        }
      }
    },
    // 以下匿名路由與 /login 同層級、不掛 DefaultLayout（跟登入頁一樣，匿名流程不需要側邊主選單）
    {
      path: '/member-register',
      name: 'member-register',
      component: () => import('@/views/account/MemberRegisterView.vue'),
    },
    // 舊路徑重新導向，避免既有連結／書籤直接變成 404
    { path: '/register', redirect: '/member-register' },
    // 後端 app.mail.verification-base-url 直接導到這個路徑，路徑名稱不可隨意更動
    {
      path: '/verify-email',
      name: 'verify-email',
      component: () => import('@/views/account/EmailVerificationView.vue'),
    },
    {
      path: '/forgot-password',
      name: 'forgot-password',
      component: () => import('@/views/account/ForgotPasswordView.vue'),
    },
    // 後端 app.mail.password-reset-base-url 直接導到這個路徑，路徑名稱不可隨意更動
    {
      path: '/reset-password',
      name: 'reset-password',
      component: () => import('@/views/account/ResetPasswordView.vue'),
    },
    {
      path: '/employee-register',
      name: 'employee-register',
      component: () => import('@/views/employee/EmployeeRegisterView.vue'),
    },
    {
      path: '/employee-reapply',
      name: 'employee-reapply',
      component: () => import('@/views/employee/EmployeeReapplyView.vue'),
    },
  ],
})

/**
 * 進入頁面前先擋下沒權限的導覽。
 *
 * <p>沒有守衛時，未登入點「預約課程」會先跑完轉場動畫、掛載頁面，
 * 等 API 回 401 才被彈走，畫面會閃一下；在這裡成功擋下就根本不會進入那頁。
 *
 * <p>這只是體驗優化，不是安全機制：真正的把關在後端的 @PreAuthorize。
 * token 在使用中過期等情況仍由 axios 攔截器的 401 處理兒式兵底。
 */
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.requiresAuth) {
    return true
  }
  if (!auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 已登入但角色不符：導回首頁，不導登入頁
  //（再登入一次也不會變成有權限，導回登入頁只會讓人困惑）
  if (to.meta.requiresRole && !auth.roles.includes(to.meta.requiresRole)) {
    return '/'
  }
  return true
})

export default router
