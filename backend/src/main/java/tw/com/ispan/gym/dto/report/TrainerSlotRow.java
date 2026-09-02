package tw.com.ispan.gym.dto.report;

/**
 * 教練時段使用率聚合列（JPQL constructor expression 投影用）。
 * 以教練為單位彙總期間內開放的時段數與被預約數；取消的時段不計，使用率由呈現端計算。
 */
public record TrainerSlotRow(
        String trainerName,
        Long totalSlots,
        Long bookedSlots) {
}
