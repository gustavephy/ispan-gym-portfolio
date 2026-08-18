<script setup>
    import { RouterView } from 'vue-router'
    import NavBar from '@/components/NavBar.vue'
</script>

<template>
  <div class="default-layout">
    <NavBar />
    <main>
      <RouterView v-slot="{ Component }">
        <Transition name="page" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>
  </div>
</template>
<style scoped>
    /* 上方的 <Transition name="page"> 一直沒有對應的 CSS，
       等於完全沒有效果；這裡補上。
       mode="out-in" 已在模板設定，所以進場與退場不會重疊。 */
    .page-enter-active,
    .page-leave-active {
    transition: opacity 0.2s ease, transform 0.2s ease;
    }
    .page-enter-from {
    opacity: 0;
    transform: translateY(8px);
    }
    .page-leave-to {
    opacity: 0;
    transform: translateY(-8px);
    }

    /* 尊重系統的「減少動態效果」設定：對前庭暈病患者，
       位移動畫會造成不適，這種情況下只保留淡入淡出。 */
    @media (prefers-reduced-motion: reduce) {
    .page-enter-active,
    .page-leave-active {
        transition: opacity 0.15s ease;
    }
    .page-enter-from,
    .page-leave-to {
        transform: none;
    }
    }

    main {
    flex: 1;
    min-height: 100vh;
    padding-left: 240px;
    min-width: 0;
    transition: padding-left 0.3s ease;
    }

    @media (max-width: 900px) {
    main {
        padding-left: 0;
        padding-top: 60px;
    }
    }
</style>