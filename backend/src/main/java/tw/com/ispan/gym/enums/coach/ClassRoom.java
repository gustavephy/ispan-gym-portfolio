package tw.com.ispan.gym.enums.coach;

import lombok.Getter;

/**
 * 團課教室。一間實體健身房的教室數量是物理限制，天生就是少量且不常變動的固定集合，
 * 故直接訂死成 enum，顏色跟著常數走，行事曆可依教室自動上色，不必每次新增教室都手動改
 * 前端顏色對照表。
 *
 * 這是本專案第一個「帶欄位的 enum」（其餘 enum 均為裸的 {@code enum X { A, B }}），
 * 放在 {@code enums/coach/} 是為了跟 {@link GroupClassSessionStatus} 放在一起方便找到。
 *
 * 若之後真的新增教室，{@code GroupClassBookingRepository}／
 * {@code GroupClassSessionRepository} 裡依教室關鍵字搜尋的兩支查詢都要跟著各多加一個
 * boolean 參數與一段 OR 子句——這是「教室數量少且穩定」這個假設本身帶來的可接受代價。
 */
@Getter
public enum ClassRoom {
    YOGA_ROOM("瑜珈教室", "#c9973f"),
    BOXING_ROOM("拳擊教室", "#4a7fb5"),
    WEIGHT_ROOM("團體重訓室", "#7a6ba6");

    private final String displayName;
    private final String colorHex;

    ClassRoom(String displayName, String colorHex) {
        this.displayName = displayName;
        this.colorHex = colorHex;
    }
}
