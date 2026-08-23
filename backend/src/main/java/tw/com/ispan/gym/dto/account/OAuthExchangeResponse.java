package tw.com.ispan.gym.dto.account;

import java.util.List;

/**
 * @param returnPath 登入前保存、已驗證過的站內相對路徑；沒有則為 null，前端自行依角色導流。
 *                   刻意不重用 {@link LoginResponse}——欄位集合不同，硬塞會讓兩個用途耦合。
 */
public record OAuthExchangeResponse(String accessToken, Integer accountId, String email, List<String> roles,
        String returnPath) {}
