import { ref, onMounted, onUnmounted } from 'vue'

// 反應式的 media query 比對，用來讓「只在窄螢幕出現的元素」真的只在窄螢幕存在於 DOM
// （純 CSS display:none 會留下隱藏的重複按鈕，測試與無障礙都會撞到）。
export function useMediaQuery(query) {
  const matches = ref(false)
  let mql = null

  function update() {
    matches.value = mql ? mql.matches : false
  }

  onMounted(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return
    mql = window.matchMedia(query)
    update()
    mql.addEventListener('change', update)
  })

  onUnmounted(() => {
    mql?.removeEventListener('change', update)
  })

  return matches
}
