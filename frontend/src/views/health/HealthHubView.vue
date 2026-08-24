<script setup>
import { RouterLink, RouterView } from 'vue-router'

const tabs = [
  { to: '/health/inbody', label: 'InBody 紀錄' },
  { to: '/health/ai-plan', label: 'AI 課表' },
  { to: '/health/chat', label: '聊天室' },
]
</script>

<template>
  <div class="page-container health-hub">
    <span class="section-tag">AI HEALTH</span>
    <h1 class="section-title">AI 健康管理與體態分析</h1>
    <p class="section-desc">
      記錄你的 InBody 量測數據，讓 AI 顧問依最新體態生成專屬課表，或直接在聊天室詢問 AI 顧問與教練。
    </p>

    <nav class="health-tabs" aria-label="AI 健康管理子頁面">
      <RouterLink
        v-for="tab in tabs"
        :key="tab.to"
        :to="tab.to"
        class="health-tabs__link"
      >
        {{ tab.label }}
      </RouterLink>
    </nav>

    <RouterView v-slot="{ Component }">
      <Transition name="page" mode="out-in">
        <component :is="Component" />
      </Transition>
    </RouterView>
  </div>
</template>

<style scoped>
.health-hub {
  padding-bottom: 4rem;
}

.health-tabs {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 2rem;
  border-bottom: 1px solid var(--border);
  flex-wrap: wrap;
}

.health-tabs__link {
  padding: 0.75rem 1.1rem;
  font-weight: 700;
  font-size: 0.95rem;
  color: var(--text-secondary);
  border-bottom: 3px solid transparent;
  transition: var(--transition);
}

.health-tabs__link:hover {
  color: var(--primary-dark);
}

.health-tabs__link.router-link-active {
  color: var(--primary-dark);
  border-bottom-color: var(--accent);
}
</style>
