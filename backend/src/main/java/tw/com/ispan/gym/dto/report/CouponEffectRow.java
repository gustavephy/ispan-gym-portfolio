package tw.com.ispan.gym.dto.report;

/**
 * 優惠券成效聚合列（JPQL constructor expression 投影用）。
 * 領取率＝claimedQuantity/totalQuantity、使用率＝usedCount/claimedQuantity，由呈現端計算。
 * 成效看的是券的整個生命週期，不隨報表期間篩選。
 */
public record CouponEffectRow(
        String name,
        String code,
        Integer totalQuantity,
        Integer claimedQuantity,
        Long usedCount) {
}
