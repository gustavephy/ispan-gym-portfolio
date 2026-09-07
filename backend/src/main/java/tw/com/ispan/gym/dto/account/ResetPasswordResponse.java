package tw.com.ispan.gym.dto.account;

/**
 * 憑 Token 重設密碼成功的回應。只回傳 token 所屬帳號的 Email，供前端做
 * 「同一個 Email 的重設完成事件」跨分頁比對（見 ForgotPasswordView 的 BroadcastChannel）。
 *
 * <p>這是「重設」端點（呼叫者已持有寄到信箱的有效 token，等同已證明能收該信箱的信），
 * 回傳 Email 不構成帳號列舉；忘記密碼「申請」端點的固定 202 反列舉設計不受影響。
 */
public record ResetPasswordResponse(String email) {
}
