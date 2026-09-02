package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/**
 * 帳號已經有本機密碼（{@code password_hash != null}）時，又嘗試呼叫「設定第一組密碼」端點。
 *
 * <p>與 {@link PasswordNotConfiguredException} 對稱：後者是「想改密碼但根本沒有密碼」，
 * 前者是「想設第一組密碼但已經有了」。已經有密碼的人要換密碼請走
 * {@code POST /api/account/me/change-password}。
 *
 * <p>併發防護：兩個請求同時進來時，Account 悲觀鎖序列化它們——第一個成功設定，第二個取得鎖
 * 之後看到 {@code password_hash} 已非 null，穩定回這個例外，不覆寫、不再次遞增
 * {@code credentialsVersion}。
 */
public class PasswordAlreadyConfiguredException extends ApiException {
    public PasswordAlreadyConfiguredException(String message) {
        super(HttpStatus.CONFLICT, "PASSWORD_ALREADY_CONFIGURED", message);
    }
}
