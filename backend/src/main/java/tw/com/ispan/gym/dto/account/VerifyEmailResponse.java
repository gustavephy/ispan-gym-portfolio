package tw.com.ispan.gym.dto.account;

/**
 * email 欄位是後補的（2026-08-25）：驗證成功時附回這個帳號的 email，
 * 讓前端能用 BroadcastChannel 跨分頁通知「原本註冊分頁」比對是不是同一個帳號再更新畫面。
 * 能打到 verify-email 端點的人前提是持有寄到這個信箱的一次性 token，等於已證明是本人，
 * 回傳這個信箱本身不構成新的帳號列舉風險。
 */
public record VerifyEmailResponse(String purpose, String email) {}
