package tw.com.ispan.gym.enums.coach;

/**
 * 後台團課場次列表的「篩選用」狀態，跟 GroupClassSessionStatus（資料庫實際存的值）
 * 不是同一件事：ENDED 不是持久層的狀態，是「status=SCHEDULED 但 endAt 已過去」這個
 * 條件的別名，資料庫欄位本身不會出現 ENDED 這個值。SCHEDULED 在這裡的語意也跟著
 * 收斂為「真的還沒發生」，否則「已排定」跟「已結束」兩個篩選條件的結果會互相重疊。
 * 轉譯邏輯見 GroupClassService#searchSessionsForAdmin。
 */
public enum GroupClassSessionFilterStatus {
    SCHEDULED, ENDED, CANCELLED
}
