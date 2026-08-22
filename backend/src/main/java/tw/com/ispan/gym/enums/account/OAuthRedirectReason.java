package tw.com.ispan.gym.enums.account;

/**
 * Callback 導回前端時可以出現在 {@code ?oauthError=} 的固定白名單值——絕不把
 * exception message、Google 回傳的原始錯誤、state、subject、email 等資訊放進
 * Redirect URL，前端只依這個列舉決定顯示文案。
 */
public enum OAuthRedirectReason {
    /** 使用者在 Google 授權頁按取消 */
    ACCESS_DENIED,
    /** transaction／registration ticket 找不到或已消耗，需重新發起 */
    FLOW_EXPIRED,
    /** 舊分頁的 transaction 已被同一 account 的新流程取代（多分頁） */
    FLOW_REPLACED,
    /** LOGIN 時 Email 已有既有 Account，需先用原登入方式確認身分再綁定 */
    ACCOUNT_LINK_REQUIRED,
    /** Google Email 尚未驗證，第一版直接拒絕 */
    EMAIL_NOT_VERIFIED,
    /** Account 無法登入（軟刪除或沒有任何有效身分） */
    ACCOUNT_DISABLED,
    /** LINK 時 Google 身分已綁定其他 Account */
    IDENTITY_IN_USE,
    /** LINK 時目前 Account 已經綁定另一個 Google 帳號（發起時應已擋下，這裡是併發下的防禦性複查） */
    ALREADY_LINKED,
    /** LINK 時 Google email 與 Account email 不一致 */
    LINK_EMAIL_MISMATCH,
    /** Google／OAuth 協定本身失敗 */
    PROVIDER_ERROR,
    /** 非預期錯誤，內部已記錄 correlation id */
    UNEXPECTED_ERROR
}
