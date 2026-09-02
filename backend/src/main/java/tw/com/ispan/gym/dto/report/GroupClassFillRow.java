package tw.com.ispan.gym.dto.report;

/**
 * 團課滿座率聚合列（JPQL constructor expression 投影用）。
 * 以課程為單位彙總期間內的場次數、報名人次與容量總和；滿座率由呈現端計算。
 */
public record GroupClassFillRow(
        String courseName,
        Long sessionCount,
        Long bookedTotal,
        Long capacityTotal) {
}
