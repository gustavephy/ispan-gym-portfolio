/**
 * 台灣手機號碼格式的單一事實來源。
 *
 * 業務規則（2026-08-31 定案，前後端一致）：必填、09 開頭、固定 10 碼。
 * 後端四個 Employee DTO（EmployeeRegisterRequest／EmployeeReapplyRequest／
 * EmployeeProfileUpdateRequest／AdminEmployeeUpdateRequest）的 @Pattern 與
 * @Size(max = 10) 對應同一條規則；前端所有輸入電話的頁面都應 import 這裡，
 * 不要各自再寫一份正則或錯誤字串，否則規則一改就會漂移。
 */

/** 台灣手機號碼格式：09 開頭 + 8 位數字，共 10 碼 */
export const TW_MOBILE_PATTERN = /^09\d{8}$/

/** 輸入框的 maxlength 與 placeholder，直接綁在 <input> 上 */
export const TW_MOBILE_MAXLENGTH = 10
export const TW_MOBILE_PLACEHOLDER = '0912345678'

/** 統一的欄位錯誤字串，格式不符時顯示 */
export const TW_MOBILE_ERROR = '請輸入正確的台灣手機號碼（09 開頭，共 10 碼）'

/**
 * 驗證是否為合法台灣手機號碼。會先 trim，空字串一律視為不合法（電話為必填）。
 * @param {string | null | undefined} value
 * @returns {boolean}
 */
export function isValidTwMobile(value) {
  return TW_MOBILE_PATTERN.test((value ?? '').trim())
}
