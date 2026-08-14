import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    //會員/公開區域
    {
      path:'/',
      component:()=>import("@/layouts/DefaultLayout.vue"),
      children:[
        {path:'',component:()=>import('@/views/home/HomeView.vue')}
      ]
    }
  ],
})

export default router
