/**
 * 團課教室相關的共用常數與工具函式。
 * 教室清單目前是固定選項（見 AdminGroupClassSessionView.vue 建立/編輯場次表單），
 * 這裡統一維護一份，避免 admin 端（下拉選單）跟 member 端（行事曆顏色）各自維護一份不同步。
 *
 * ROOM_COLOR_MAP 讓每間教室在會員端行事曆上有固定顏色，方便重疊課程時用顏色辨認教室。
 * 舊資料或測試留下的、不在這份清單內的教室名稱（例如「體能教室B」），會 fallback 到灰色，
 * 不會報錯，只是顏色比較不特別。
 */

export const ROOM_OPTIONS = ['瑜珈教室', '拳擊教室', '團體重訓室', '多功能體能教室']

const ROOM_COLOR_MAP = {
  '瑜珈教室': '#c9973f',
  '拳擊教室': '#4a7fb5',
  '團體重訓室': '#7a6ba6',
  '多功能體能教室': '#5a9e6f',
}

const FALLBACK_ROOM_COLOR = '#8a8a8a'

/** 依教室名稱取得對應的行事曆顏色，找不到對應教室時回傳灰色 fallback。 */
export function getRoomColor(roomName) {
  return ROOM_COLOR_MAP[roomName] || FALLBACK_ROOM_COLOR
}
