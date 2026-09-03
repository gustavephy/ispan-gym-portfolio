import api from './axios.instance.js'

// 後台系統監控，對應後端 SystemMonitorController 的 /api/admin/system。
// 指標由 actuator/Micrometer 在後端蒐集，這裡拿到的是彙總後的一包 JSON；
// /actuator/** web 端點刻意不開放（見後端 application.properties 的說明）。

/** 即時系統指標（DB 健康、JVM、CPU、連線池、HTTP 統計），需 ADMIN */
export const getSystemMetrics = () => api.get('/api/admin/system/metrics')
