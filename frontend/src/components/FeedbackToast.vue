<script setup>
defineProps({
  feedback: { type: Object, default: null }, // { text, type: 'success' | 'error' }
})
</script>

<template>
  <div v-if="feedback" class="feedback-toast" :class="feedback.type">{{ feedback.text }}</div>
</template>

<style scoped>
.feedback-toast {
  position: fixed;
  bottom: 2rem;
  left: 50%;
  transform: translateX(-50%);
  padding: 0.7rem 1.5rem;
  border-radius: 999px;
  font-size: 0.88rem;
  font-weight: 700;
  color: #fff;
  z-index: 1000;
}
.feedback-toast.success { background: #2f9e44; }
.feedback-toast.error { background: #e03131; }
</style>
