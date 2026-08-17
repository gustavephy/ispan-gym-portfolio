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