// 共用課程類型判斷工具
// 人數上限為 1 人 → 私教課；超過 1 人 → 團課

// 判斷是否為私教課（上限 1 人）
// @param {Object} course - 課程或商品物件，需含 maxCapacity
// @returns {boolean}
export function isCoursePrivate(course) {
  if (!course) return false
  if (course.courseType === 'PRIVATE') return true
  if (course.courseType === 'GROUP') return false
  if (Number(course.maxCapacity) === 1) return true
  const name = course.name || ''
  const desc = course.description || ''
  const size = course.classSize || ''
  return name.includes('一對一') || name.includes('私教') ||
         desc.includes('一對一') || desc.includes('私教') ||
         size.includes('1') || size.includes('一對一')
}

// 取得課程類型標籤文字與 CSS class
// @param {Object} course
// @returns {{ label: string, badgeClass: string }}
export function getCourseTypeInfo(course) {
  if (isCoursePrivate(course)) {
    return { label: '私教課', badgeClass: 'badge-private' }
  }
  return { label: '團課', badgeClass: 'badge-group' }
}
