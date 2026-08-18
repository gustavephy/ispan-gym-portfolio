import { ref } from 'vue'

// 短暫顯示的提示訊息（成功／失敗），供頁面搭配 <FeedbackToast> 使用
export function useFeedback(duration = 3000) {
  const feedback = ref(null) // { text, type }
  let timer = null

  function showFeedback(text, type = 'success') {
    feedback.value = { text, type }
    clearTimeout(timer)
    timer = setTimeout(() => { feedback.value = null }, duration)
  }

  return { feedback, showFeedback }
}
