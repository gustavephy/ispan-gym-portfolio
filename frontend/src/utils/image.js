// 圖片相關工具函數

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
