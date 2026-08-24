package tw.com.ispan.gym.dto.course;

import java.math.BigDecimal;

/**
 * 編輯私教課商品時可更新的欄位，跟建立用的 {@link PrivateCourseRequest} 分開：不含
 * `trainerId`（授課教練不可透過修改變更，換教練需另開商品）、不含 `status`（狀態只能透過
 * ADMIN 的下架／重新上架端點改變，避免兩條路徑都能改狀態、繞過重新上架時該做的教練
 * 可服務狀態檢查——見 PrivateCourseService#activateByAdmin()）。比照
 * {@link UpdateGroupCourseRequest} 拆分建立／編輯 DTO 的既有慣例（code review 發現，
 * 2026-08-19）。
 */
public record UpdatePrivateCourseRequest(
        String name,
        BigDecimal price,
        String description,
        String imageUrl,
        Boolean isTrial,
        Integer maxCapacity,
        Integer sessionPerUnit,
        Integer courseCategoryId
) {}
