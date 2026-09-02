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

/** 歷史報表列表（不含明細），需 ADMIN */
export const getReports = () => api.get('/api/admin/reports')

/**
 * 單份報表快照，含排行明細、狀態／付款分佈、每日營收與退款統計，需 ADMIN。
 * 註：延伸統計是 2026-08-17 才擴充的，更早生成的快照這些欄位會是 null／空陣列，
 * 呈現端必須容忍（見 ReportDetailResponse 的類別註解）。
 */
export const getReportDetail = (reportId) => api.get(`/api/admin/reports/${reportId}`)

/**
 * 生成報表快照，需 ADMIN
 * @param {{reportType: string, periodStart: string, periodEnd: string}} payload
 *   reportType 為 DAILY／MONTHLY／CUSTOM（後端 ReportType 只有這三個值）
 */
export const generateReport = (payload) => api.post('/api/admin/reports', payload)

/** 刪除報表快照，需 ADMIN */
export const deleteReport = (reportId) => api.delete(`/api/admin/reports/${reportId}`)

/** 下載報表 PDF（回傳 blob，呼叫端自行轉成下載連結），需 ADMIN */
export const downloadReportPdf = (reportId) =>
  api.get(`/api/admin/reports/${reportId}/pdf`, { responseType: 'blob' })

/** 下載品項排行 CSV（回傳 blob），需 ADMIN */
export const downloadReportCsv = (reportId) =>
  api.get(`/api/admin/reports/${reportId}/csv`, { responseType: 'blob' })
