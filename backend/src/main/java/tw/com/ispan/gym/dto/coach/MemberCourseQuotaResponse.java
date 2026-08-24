package tw.com.ispan.gym.dto.coach;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 會員課程包額度查詢回應。remaining/reserved/used 三個欄位的意義見
 * {@link tw.com.ispan.gym.entity.coach.MemberCourseQuota} 上的說明；
 * total 與 status 是 Entity 上的衍生值（getTotal()/getStatus()），
 * 這裡直接快照成固定值，不是即時計算欄位。
 */
@Data
@AllArgsConstructor
public class MemberCourseQuotaResponse {
    private Integer id;
    private Integer trainerId;
    private String trainerName;
    private String courseName;
    private Integer remainingCourse;
    private Integer reservedCourse;
    private Integer usedCourse;
    private int total;
    private String status;
    private LocalDateTime expiryDate;
}
