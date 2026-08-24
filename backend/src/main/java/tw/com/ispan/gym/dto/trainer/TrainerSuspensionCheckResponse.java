package tw.com.ispan.gym.dto.trainer;

/**
 * 停用預檢用（GET /api/admin/trainers/{trainerId}/suspension-check），只給管理畫面提示。
 * 正式停用（POST .../suspend）仍須在交易內重新檢查，不可信任這裡的結果已經過期。
 */
public record TrainerSuspensionCheckResponse(boolean canSuspend, Blockers blockers) {

    public record Blockers(long openSlots, long activeBookings, long activePrivateCourses,
            long upcomingGroupClassSessions) {

        public boolean any() {
            return openSlots > 0 || activeBookings > 0 || activePrivateCourses > 0
                    || upcomingGroupClassSessions > 0;
        }
    }

    public static TrainerSuspensionCheckResponse from(Blockers blockers) {
        return new TrainerSuspensionCheckResponse(!blockers.any(), blockers);
    }
}
