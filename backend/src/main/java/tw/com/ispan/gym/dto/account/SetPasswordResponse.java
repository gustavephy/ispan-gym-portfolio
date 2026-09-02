package tw.com.ispan.gym.dto.account;

import java.util.List;

/**
 * OAuth-only 帳號設定第一組本機密碼成功後，回傳一組新簽發的 JWT（{@code cv} 已是
 * 遞增後的版本），前端原地替換舊 token 即可讓目前這個裝置以新 JWT 繼續登入。
 *
 * <p>欄位形狀刻意與 {@link ChangePasswordResponse}／{@link LoginResponse} 一致（前端
 * {@code auth.setSession()} 直接吃這個形狀），但仍維持每個 use case 各自一個 DTO 的慣例，
 * 不共用。
 */
public record SetPasswordResponse(String accessToken, Integer accountId, String email, List<String> roles) {}
