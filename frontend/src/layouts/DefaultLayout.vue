<script setup>
import { ref, provide } from 'vue'
import { RouterView } from 'vue-router'
import NavBar from '@/components/NavBar.vue'
import FeedbackToast from '@/components/FeedbackToast.vue'

const toast = ref(null)
let toastTimer = null

function showToast(text, type = 'success') {
  if (toastTimer) clearTimeout(toastTimer)
  toast.value = { text, type }
  toastTimer = setTimeout(() => { toast.value = null }, 3000)
}

provide('showToast', showToast)
</script>

<template>
  <div class="default-layout">
    <NavBar />
    <main>
      <RouterView v-slot="{ Component }">
        <keep-alive :include="['OrderListView','MemberCourseView','MemberProfileView','MemberCouponView','AccountSecurityView']">
          <component :is="Component" />
        </keep-alive>
      </RouterView>
    </main>
    <FeedbackToast :feedback="toast" />
  </div>
</template>
<style scoped>

    main {
    flex: 1;
    min-height: 100vh;
    padding-left: 240px;
    min-width: 0;
    }

    @media (max-width: 900px) {
    main {
        padding-left: 0 !important;
        padding-top: 60px;
    }
    }
</style>