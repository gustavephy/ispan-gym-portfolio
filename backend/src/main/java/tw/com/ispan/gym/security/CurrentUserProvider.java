package tw.com.ispan.gym.security;


/**
 * 取得目前登入者的識別 id。
 *
 * <p>member／employee 改用獨立流水號後，account id 與各身分表的 id 是不同的值，
 * 呼叫端必須依用途選對方法，不可混用：
 * <ul>
 * <li>{@link #requireCurrentUserId()} — 帳號 id（JWT subject），給需要跨身分
 * 或後續自行反查身分表（如 account → employee → trainer）的模組。</li>
 * <li>{@link #requireCurrentMemberId()} — 會員 id（member 表主鍵），給購物車、
 * 訂單、預約等以 member_id 為 FK 的會員域模組。</li>
 * </ul>
 */
public interface CurrentUserProvider {

    /** 目前登入者的帳號 id（account 表主鍵） */
    Integer requireCurrentUserId();

    /** 目前登入者的會員 id（member 表主鍵）；帳號沒有會員身分時拋例外 */
    Integer requireCurrentMemberId();
}
