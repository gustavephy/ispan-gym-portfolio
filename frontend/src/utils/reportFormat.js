// 營運統計與報表快照兩頁共用的日期／數字格式化工具與圖表配色。
// 兩頁拆開後仍須呈現一致的數字與色彩，抽在這裡而不是各自複製一份。

/** yyyy-MM-dd。不用 toISOString()：那會先轉 UTC，台灣時區晚上 8 點後會少算一天 */
export function isoDate(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** n 天前的 Date */
export function daysAgo(n) {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d
}

/** ISO 日期時間字串 → 「yyyy-MM-dd HH:mm」 */
export function formatDateTime(value) {
  return String(value ?? '')
    .replace('T', ' ')
    .slice(0, 16)
}

/** ISO 日期字串 → 「yyyy/MM/dd」；空值顯示破折號 */
export function formatDate(value) {
  if (!value) return '—'
  return value.slice(0, 10).replace(/-/g, '/')
}

/** 新台幣，不顯示小數 */
export const currency = new Intl.NumberFormat('zh-TW', {
  style: 'currency',
  currency: 'TWD',
  maximumFractionDigits: 0,
})

/** 千分位整數 */
export const number = new Intl.NumberFormat('zh-TW')

// 取自 dataviz 參考色盤（已用 validate_palette.js 驗證通過 light／dark 兩種模式）
export const SERIES_1 = '#2a78d6'
export const MUTED = '#9a8f88'
export const INK = '#2b2320'
/** 圓餅的類別色：首色沿用 SERIES_1，其餘取同明度的對比色相 */
export const CATEGORICAL = [SERIES_1, '#e8933d', '#43a06d', '#9268c9', '#c75d76']
