package tw.com.ispan.gym.dto.account;

import java.util.List;

/**
 * 修改密碼成功後回傳一組新簽發的 JWT（cv 已是遞增後的版本），
 * 前端原地替換掉舊 token 即可維持登入，不必重新輸入密碼。
 * 獨立於 {@link LoginResponse}，即使欄位目前相同，也不讓兩個不同情境共用同一個 DTO。
 */
public record ChangePasswordResponse(String accessToken, Integer accountId, String email, List<String> roles) {}
