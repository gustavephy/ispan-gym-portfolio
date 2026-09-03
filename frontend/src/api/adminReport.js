import api from './axios.instance.js'

// 後台營運統計，對應後端 OperationReportController 的 /api/admin/reports。
// 兩種資料來源職責不同：
//   - /stats  即時統計，不落快照，改期間就重算，適合互動式篩選
//   - /{id}   歷史報表快照，生成當下凍結，事後退款不會改動（staleAt 會標記已過期）

/**
 * 即時銷售統計，需 ADMIN。
 * @param {{from: string, to: string, type?: string, top?: number}} params
 *   from／to 為 yyyy-MM-dd，含頭含尾；type 省略代表全部品項
 *   （PRODUCT／COURSE／SUBSCRIPTION）；top 為排行取前幾名
 */
export const getLiveStats = ({ from, to, type = '', top = 10 }) =>
  api.get('/api/admin/reports/stats', {
    // 空字串會讓後端 enum 轉換回 400，轉成 undefined 讓 axios 不帶這個 query
    params: { from, to, type: type || undefined, top },
  })

/**
 * 即時營運總覽（訂單/營收/狀態與付款方式分布/退款/新會員新訂閱），需 ADMIN。
 * 端點掛在 AdminOrderController 下；參數是 ISO DATE_TIME，
 * 呼叫端自行把日期轉成 [起日 00:00, 迄日+1 00:00) 的半開區間（口徑同 /stats）。
 * 注意時間軸與 /stats 不同：這裡統計「下單日 createdAt」，看的是下單量而非營收歸屬。
 */
export const getOrderOverview = ({ from, to }) =>
  api.get('/api/admin/orders/reports/overview', { params: { from, to } })

/** 團課滿座率（即時，不落快照），需 ADMIN。from／to 為 yyyy-MM-dd，時間軸為場次開始時間 */
export const getGroupClassFill = ({ from, to }) =>
  api.get('/api/admin/reports/group-class-fill', { params: { from, to } })

/** 每日新會員趨勢（即時，不落快照），需 ADMIN。無人註冊的日期不回列，呈現端自行補零 */
export const getMemberGrowth = ({ from, to }) =>
  api.get('/api/admin/reports/member-growth', { params: { from, to } })

/** 教練時段使用率（即時，不落快照），需 ADMIN。時間軸為時段開始時間，取消時段不計 */
export const getTrainerUtilization = ({ from, to }) =>
  api.get('/api/admin/reports/trainer-utilization', { params: { from, to } })

/** 優惠券成效（即時，看券的整個生命週期），需 ADMIN。不吃期間參數 */
export const getCouponEffect = () => api.get('/api/admin/reports/coupon-effect')

/**
 * 歷史報表列表（不含明細），需 ADMIN。只列現行版；預設不含已作廢，
 * includeVoided 為 true 才連作廢的一起列（作廢的現行版是該區間歷史版本的入口）
 */
export const getReports = ({ includeVoided = false } = {}) =>
  api.get('/api/admin/reports', { params: { includeVoided: includeVoided || undefined } })

/**
 * 單份報表快照，含排行明細、狀態／付款分佈、每日營收與退款統計，需 ADMIN。
 * 註：延伸統計是 2026-08-17 才擴充的，更早生成的快照這些欄位會是 null／空陣列，
 * 呈現端必須容忍（見 ReportDetailResponse 的類別註解）。
 */
export const getReportDetail = (reportId) => api.get(`/api/admin/reports/${reportId}`)

/** 同型別同區間的全部版本（現行版第一筆）；append-only 版本化的歷史入口，需 ADMIN */
export const getReportVersions = (reportId) => api.get(`/api/admin/reports/${reportId}/versions`)

/**
 * 生成報表快照，需 ADMIN
 * @param {{reportType: string, periodStart: string, periodEnd: string}} payload
 *   reportType 為 DAILY／MONTHLY／CUSTOM（後端 ReportType 只有這三個值）
 */
export const generateReport = (payload) => api.post('/api/admin/reports', payload)

/**
 * 作廢報表快照（現行版或歷史版皆可），需 ADMIN。快照不提供刪除——報表是稽核紀錄，
 * 誤生成只能作廢：清單預設隱藏、資料保留，記錄作廢人與原因（reason 選填，最多 200 字）
 */
export const voidReport = (reportId, reason) =>
  api.post(`/api/admin/reports/${reportId}/void`, { reason: reason || null })

/** 下載報表 PDF（回傳 blob，呼叫端自行轉成下載連結），需 ADMIN */
export const downloadReportPdf = (reportId) =>
  api.get(`/api/admin/reports/${reportId}/pdf`, { responseType: 'blob' })

/** 下載品項排行 CSV（回傳 blob），需 ADMIN */
export const downloadReportCsv = (reportId) =>
  api.get(`/api/admin/reports/${reportId}/csv`, { responseType: 'blob' })
