// 24 小時、每 10 分鐘一個選項，寫法比照 TrainerScheduleView.vue
// 只開放 10:00~22:00、每 10 分鐘一個選項（含 22:00 這個端點），對應健身房營業時間。
// length 73 = 從 10:00 到 22:00 共 72 個 10 分鐘間隔 + 1（含頭尾兩端點）
export const TIME_OPTIONS = Array.from({ length: 73 }, (_, i) => {
  const totalMinutes = 10 * 60 + i * 10
  const hh = String(Math.floor(totalMinutes / 60)).padStart(2, '0')
  const mm = String(totalMinutes % 60).padStart(2, '0')
  return `${hh}:${mm}`
})
