/**
 * 全站例外處理。
 *
 * <h2>新增例外時怎麼做</h2>
 *
 * 繼承 {@link tw.com.ispan.gym.exception.ApiException}，在建構子指定狀態碼與錯誤代碼即可，
 * <strong>不需要修改 GlobalExceptionHandler</strong>：
 *
 * <pre>{@code
 * public class CourseFullException extends ApiException {
 *     public CourseFullException(Integer courseId) {
 *         super(HttpStatus.CONFLICT, "COURSE_FULL", "課程已額滿：" + courseId);
 *     }
 * }
 * }</pre>
 *
 * 錯誤代碼用大寫底線，前端以此判斷錯誤類型，訂好後不要隨意更動。
 * 訊息會原樣回傳給前端，<strong>不可包含 SQL、檔案路徑、類別名稱</strong>等系統內部資訊。
 *
 * <h2>回應格式</h2>
 *
 * 所有錯誤一律回傳 {@link tw.com.ispan.gym.exception.ApiErrorResponse}，
 * 前端可用同一套邏輯處理：
 *
 * <pre>{@code
 * { "code": "INSUFFICIENT_STOCK", "message": "商品庫存不足：...", "timestamp": "2026-08-10T14:04:29" }
 * }</pre>
 *
 * <h2>兩條路徑，同一種格式</h2>
 *
 * 請求會經過兩道關卡，各自有不同的錯誤出口，但產生的 JSON 相同：
 *
 * <pre>
 * HTTP Request
 *      │
 *      ▼
 * ┌────────────────────────────┐
 * │ Security Filter Chain      │  JwtAuthFilter → 授權規則
 * └────────┬───────────────────┘
 *          │
 *     ✗ 被擋下                    ✓ 放行
 *          │                        │
 *          ▼                        ▼
 * JsonAuthenticationEntryPoint   Controller → Service → Entity
 * JsonAccessDeniedHandler                 │
 *   （見 tw.com.ispan.gym.security）    ✗ 丟出例外
 *          │                              │
 *          │                              ▼
 *          │                    GlobalExceptionHandler
 *          │                    （@RestControllerAdvice）
 *          │                              │
 *          └──────────┬───────────────────┘
 *                     ▼
 *              ApiErrorResponse
 * </pre>
 *
 * <p><strong>為什麼需要兩套：</strong>{@code @RestControllerAdvice} 屬於 Spring MVC，
 * 掛在 DispatcherServlet 之後。請求若在 filter 層就被擋下，根本走不到 controller，
 * 這個 handler 沒有機會執行，因此 filter 層必須自行輸出 JSON
 * （見 {@code tw.com.ispan.gym.security.ApiErrorWriter}）。
 *
 * <h2>GlobalExceptionHandler 的比對順序</h2>
 *
 * Spring 依「型別最接近」挑選 handler，因此愈精確的愈優先：
 *
 * <ol>
 *   <li>{@code ApiException} — 專案自訂例外，狀態碼與代碼由例外自帶</li>
 *   <li>{@code MethodArgumentNotValidException} — {@code @Valid} 驗證失敗，400</li>
 *   <li>{@code AuthenticationException} — service 層丟出的未認證，401</li>
 *   <li>{@code AccessDeniedException} — {@code @PreAuthorize} 擋下，403</li>
 *   <li>{@code Exception} — 兜底，500，細節只寫 log 不外洩</li>
 * </ol>
 *
 * <h2>注意事項</h2>
 *
 * <ul>
 *   <li><strong>不要在 entity 的 {@code @PrePersist} / {@code @PreUpdate} 做驗證。</strong>
 *       那裡丟出的例外會被 Hibernate 包成 {@code PersistenceException}，
 *       型別已非原始例外，一定會落到兜底變成 500。驗證請放在 DTO
 *       （{@code @NotNull} 等）或 service 層。
 *       {@code ChatMessage}、{@code InbodyRecord} 目前仍有這種寫法，待接上 controller 時處理。</li>
 *   <li><strong>引入新的框架例外時要想一下。</strong>兜底會接住所有未列舉的例外，
 *       包括那些本身就有正確狀態碼的框架例外（如 {@code AccessDeniedException} 本該是 403）。
 *       若發現某種錯誤莫名變成 500，多半是少了對應的 {@code @ExceptionHandler}。</li>
 *   <li>檔案一律以 UTF-8 存檔，否則中文訊息會變成無效位元組
 *       （專案已在 {@code .vscode/settings.json} 設定）。</li>
 * </ul>
 */
package tw.com.ispan.gym.exception;
