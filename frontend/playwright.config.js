import { defineConfig, devices } from '@playwright/test'

/**
 * E2E 測試設定。
 *
 * <p>前後端都由 webServer 自動帶起：後端用 mvnw、前端用 vite。已經有服務在跑時
 * reuseExistingServer 會直接沿用，不會重複啟動（本機開發時最常見的情況）。
 *
 * <p>只跑 chromium：這個專案的目標使用者是後台管理與會員瀏覽，沒有跨瀏覽器需求，
 * 多開兩個引擎只是讓每次跑測試多花三倍時間。真的需要時再加回 firefox/webkit。
 */
export default defineConfig({
  testDir: './e2e',
  // 測試之間共用同一個開發資料庫，平行跑會互相污染（同一會員的購物車、訂單節流…）
  fullyParallel: false,
  workers: 1,
  // CI 上不允許把 test.only 留在程式碼裡
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'list' : [['html', { open: 'never' }], ['list']],

  timeout: 60_000,
  expect: { timeout: 10_000 },

  use: {
    baseURL: 'http://localhost:5173',
    // 失敗時才留下追蹤與截圖，成功的案例不產生任何檔案
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
    locale: 'zh-TW',
    timezoneId: 'Asia/Taipei',
  },

  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],

  webServer: [
    {
      // 後端啟動含 Hibernate 建表與播種，第一次會慢，給到 5 分鐘
      command: './mvnw -o spring-boot:run',
      cwd: '../backend',
      url: 'http://localhost:8080/api/products?page=0&size=1',
      reuseExistingServer: true,
      timeout: 300_000,
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: 'npm run dev',
      url: 'http://localhost:5173',
      reuseExistingServer: true,
      timeout: 120_000,
      stdout: 'ignore',
      stderr: 'pipe',
    },
  ],
})
