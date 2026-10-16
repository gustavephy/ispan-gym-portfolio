# 威力健身房管理系統 (Power Gym Management System)

本專案為 6 人團隊協同開發之企業級健身房營運管理系統，涵蓋會員服務、金流交易、商品商城、預約排程與營運報表等業務範疇。

個人在團隊中擔任全端核心開發者，獨立負責「商城商品、課程管理、優惠券系統、會籍方案」四大核心模組的前後端架構設計、資料庫建模、RESTful API 實作、前端介面開發與系統整合。

---

## 1. 系統技術堆疊

| 架構層級 | 技術選型 |
|---|---|
| 後端框架 | Java 25 / Spring Boot 4.1.0 / Spring Security (JWT) / Spring Data JPA |
| 資料庫 | Microsoft SQL Server 2022 (MSSQL) |
| 前端架構 | Vue 3 (Composition API) / Vite / Pinia / Vue Router / Axios |
| 多媒體服務 | Cloudinary 雲端圖床 / 本機磁碟儲存雙軌備援 |
| 測試與部署 | JUnit 5 / 併發壓力測試 / Docker / Docker Compose |

---

## 2. 個人負責模組與業務範疇

- 商城商品模組：商品型錄多條件篩選、規格展示、即時庫存狀態提示、後台商品與庫存維護、結帳庫存連動扣減。
- 優惠券模組：促銷優惠券發行管理、會員個人錢包（分頁檢視與狀態過濾）、結帳門檻折抵計算、限量優惠券併發搶領。
- 課程管理模組：團體課程與私人教練課程方案 CRUD、課程分類階層維護、前台清單展示與後台課程主檔管理。
- 會籍方案模組：多元會籍方案公開瀏覽、會員個人合約起訖日與剩餘天數查詢、直接訂閱結帳整合。

---

## 3. 核心技術亮點與工程實踐

### 3.1 併發交易處理：JPA 悲觀寫入鎖防止超領與超賣
在促銷優惠券搶領與熱門商品結帳扣庫存的情境下，為避免競態條件 (Race Condition) 導致超領或超賣，採用以下機制：
- 於 Repository 宣告 `@Lock(LockModeType.PESSIMISTIC_WRITE)` 施加資料庫排他列鎖，搭配 `@Transactional` 將檢查與扣減封裝於同一交易邊界內。
- 撰寫多執行緒併發壓測（`CountDownLatch` 搭配 `ExecutorService`），實測 1,000 位會員同時搶領限量 10 張優惠券，成功數精準為 10、失敗 990，資料庫數值完全一致。
- 實作位置：`backend/src/main/java/tw/com/ispan/gym/service/coupon/MemberCouponService.java`
- 壓測驗證：`backend/src/test/java/tw/com/ispan/gym/service/coupon/MemberCouponConcurrencyTest.java`

### 3.2 商城多條件查詢與樂觀鎖並行控制
為滿足商城前台多維度交叉篩選並兼顧伺服器記憶體負載：
- 使用 Spring Data JPA `@Query` 搭配 JPQL 條件語法 `(:param IS NULL OR field = :param)`，以靜態查詢語句優雅支援分類、關鍵字、品牌、口味、價格區間等多條件可選組合篩選。
- 搭配 `Pageable` 實現分頁資料傳輸，避免一次載入大量資料導致記憶體負載過大。
- 管理端商品實體導入 `@Version` 樂觀鎖機制，在多位管理員並行更新商品時防止資料覆寫 (Lost Update)。
- 查詢介面：`backend/src/main/java/tw/com/ispan/gym/repository/product/ProductRepository.java`
- 實體配置：`backend/src/main/java/tw/com/ispan/gym/entity/product/Product.java`

### 3.3 MD5 數位指紋圖片去重與雲端/本機雙軌儲存
為避免使用者重複上傳相同檔案導致儲存容量浪費與頻寬消耗：
- 嚴格校驗 MIME Type 與副檔名白名單，並透過 `MessageDigest.getInstance("MD5")` 計算檔案 128 位元二進位雜湊值作為唯一資源識別碼 (public_id)。
- 上傳前透過 Cloudinary API 比對雲端既有資源；若檔案已存在則直接沿用安全傳輸 URL，完全省略重複傳輸。
- 內建容錯降級機制，未設定雲端金鑰時自動降級寫入本機磁碟，同樣以 MD5 檔名防重，確保無外網或開發環境下的高可用性。
- 實作位置：`backend/src/main/java/tw/com/ispan/gym/service/file/FileService.java`

### 3.4 課程 CRUD API 設計與宣告式雙層權限控管
- 依循標準 RESTful 規範設計課程及分類端點，採用 DTO 與 Bean Validation 嚴格校驗請求邊界。
- 結合 Spring Security 實作雙層防禦：第一層以 `SecurityConfig` 針對 `/api/admin/**` 路徑進行全域攔截；第二層於 Controller 方法加註 `@PreAuthorize("hasRole('ADMIN')")`，以宣告式安全防護機敏操作。
- 授權端點：`backend/src/main/java/tw/com/ispan/gym/controller/course/AdminGroupCourseController.java`
- 安全配置：`backend/src/main/java/tw/com/ispan/gym/config/SecurityConfig.java`

### 3.5 Vue 3 現代化前端架構與狀態管理
- 全面採用 Vue 3 Composition API 與 `<script setup>` 實作元件化開發，提升程式碼可讀性與維護性。
- 使用 Pinia 模組化管理商品篩選條件與快取資料集，降低跨元件傳遞成本並減少冗餘 API 呼叫。
- 封裝 Axios 實例與全域攔截器，自動注入 JWT 授權標頭，並統一處理 401、403、400 等例外狀態提示。
- 實作位置：`frontend/src/views/`、`frontend/src/stores/`

---

## 4. 併發防護機制技術比較

| 評估項目 | 悲觀鎖定 (Pessimistic Locking) | 樂觀鎖定 (Optimistic Locking) |
|---|---|---|
| 實作技術 | `@Lock(LockModeType.PESSIMISTIC_WRITE)` | `@Version` 欄位版本機制 |
| 適用情境 | 優惠券限量搶領、結帳商品庫存扣減 | 後台商品資訊維護、課程資料更新 |
| 併發特性 | 高競爭、衝突頻繁、不允許超賣或超領 | 低至中度衝突、讀多寫少 |
| 處理原則 | 事先鎖定：資料庫列鎖排隊，確保交易互斥 | 事後比對：提交時比對版本號，衝突時回滾 |
| 專案防護目標 | 防止庫存出現負數、防止優惠券溢發 | 防止多位管理員同時編輯時資料被覆寫 |

---

## 5. 本機開發環境啟動指南

### 5.1 前置需求
- JDK 25、Node.js 20+、Docker Desktop
- SQL Server 於 localhost:1433，建立資料庫 `ispan-proj-gymdb`

### 5.2 啟動指令

後端啟動 (Port: 8080)：
```bash
cd backend
set -a && . ../.env && set +a && ./mvnw spring-boot:run
```

前端啟動 (Port: 5173)：
```bash
cd frontend
npm install
npm run dev
```

Docker 全容器一鍵啟動：
```bash
docker compose up -d --build
```