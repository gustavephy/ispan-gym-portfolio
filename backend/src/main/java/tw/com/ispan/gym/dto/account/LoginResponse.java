package tw.com.ispan.gym.dto.account;

import java.util.List;

/**
 * @param roles 展開後的有效權限（去 ROLE_ 前綴），如 [MEMBER]、[EMPLOYEE, ADMIN]、
 *              [EMPLOYEE, TRAINER]，供前端決定導向；空陣列＝帳號沒有任何已啟用身分
 *              （如信箱未驗證）。真正的授權仍由後端每請求檢查，此欄位僅供 UI 決策。
 */
public record LoginResponse(String accessToken, Integer accountId, String email, List<String> roles) {}
