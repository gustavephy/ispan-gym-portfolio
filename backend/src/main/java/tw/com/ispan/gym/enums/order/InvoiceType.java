package tw.com.ispan.gym.enums.order;

/**
 * 訂單發票開立方式。目前僅記錄使用者在結帳時的選擇並顯示於訂單明細，
 * 尚未串接綠界電子發票開立 API。
 *
 * <ul>
 *   <li>{@code PERSONAL_CARRIER} — 個人（會員載具／雲端發票），不需額外資料</li>
 *   <li>{@code MOBILE_BARCODE} — 手機條碼載具，需帶 {@code carrier}（格式 {@code ^/[0-9A-Z.+-]{7}$}）</li>
 *   <li>{@code COMPANY} — 公司戶（統一編號），需帶 {@code taxId}（8 碼數字）</li>
 *   <li>{@code NPO_DONATION} — 捐贈給社福團體，需帶 {@code donationCode}（愛心碼 3~7 碼數字）</li>
 * </ul>
 */
public enum InvoiceType { PERSONAL_CARRIER, MOBILE_BARCODE, COMPANY, NPO_DONATION }
