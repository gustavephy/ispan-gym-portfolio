package tw.com.ispan.gym.enums.account;

/**
 * PENDING：等待 Google callback。CONSUMED：成功完成（LOGIN 已判定或 LINK 已綁定）。
 * FAILED：流程本身失敗（例如使用者在 Google 頁取消）。SUPERSEDED：被同一
 * account+provider+purpose 的新流程取代（多分頁重新發起）。
 */
public enum OAuthTransactionStatus { PENDING, CONSUMED, FAILED, SUPERSEDED }
