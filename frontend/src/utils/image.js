// 圖片相關工具函數

/**
 * 商品／課程的預設封面圖。
 *
 * 兩種情況會用到：image_url 是空的，或有值但檔案不存在——上傳的圖存在後端的
 * uploads 目錄且未進版控，組員 clone 後資料庫有路徑卻沒有檔案，不接手就會是破圖。
 */
export const DEFAULT_PRODUCT_IMAGE =
  'https://images.unsplash.com/photo-1583454110551-21f2fa2afe61?w=400&auto=format&fit=crop&q=60'
export const DEFAULT_COURSE_IMAGE =
  'https://images.unsplash.com/photo-1517838277536-f5f99be501cd?w=400&auto=format&fit=crop&q=60'

/**
 * 圖片載入失敗時改用預設圖，綁在 <img> 的 @error 上。
 *
 * 換圖後先清掉 onerror，避免預設圖本身也載入失敗時無限重觸發。
 * @param {Event} event img 的 error 事件
 * @param {string} fallback 預設圖網址
 */
export function useFallbackImage(event, fallback = DEFAULT_PRODUCT_IMAGE) {
  const img = event.target
  if (!img || img.src === fallback) return
  img.onerror = null
  img.src = fallback
}

// 格式化圖片 URL，處理本地開發環境 localhost:8080 網址在 Vercel 等線上部署環境中的重寫對接
// @param {string} url 原始圖片 URL
// @returns {string} 格式化後的正確網址
export function formatImageUrl(url) {
  if (!url || typeof url !== 'string') return ''

  // 1. 如果是舊資料中寫死的 localhost:8080 或 localhost:9080 網址，先替換為相對路徑 /uploads/...
  let processedUrl = url
  if (url.includes('localhost:8080/uploads/') || url.includes('localhost:9080/uploads/')) {
    processedUrl = url.replace(/http:\/\/localhost:(8080|9080)/, '')
  }

  // 2. 如果有設定 VITE_API_URL，則補上對應的前綴路徑
  const apiUrl = import.meta.env.VITE_API_URL
  if (processedUrl.startsWith('/uploads/') && apiUrl) {
    const base = apiUrl.endsWith('/') ? apiUrl.slice(0, -1) : apiUrl
    return base + processedUrl
  }

  return processedUrl
}
