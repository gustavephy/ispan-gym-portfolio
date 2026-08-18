package tw.com.ispan.gym.service.course;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.course.GroupCourseRequest;
import tw.com.ispan.gym.dto.course.GroupCourseResponse;
import tw.com.ispan.gym.entity.course.CourseCategory;
import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.course.CourseCategoryRepository;
import tw.com.ispan.gym.repository.course.GroupCourseRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * 管理員維護團課主檔（GroupCourse）的商業邏輯：建立與查詢。
 * 場次（GroupClassSession）相關邏輯在 {@code GroupClassService}，職責分開——
 * 這裡只管「課程本身長怎樣」（教練、名稱、容量……），不涉及任何特定場次的時間、教室、報名狀態。
 */
@Service
@RequiredArgsConstructor
public class GroupCourseService {

    private final GroupCourseRepository groupCourseRepository;
    private final TrainerRepository trainerRepository;
    private final CourseCategoryRepository courseCategoryRepository;

    /**
     * 建立團課主檔。
     * isTrial／status 允許前端不填，這裡補上預設值（false／ACTIVE）——刻意不放在
     * DTO 欄位的 initializer：Lombok @Data 產生的物件在 JSON 反序列化時，
     * Jackson 是先建構空物件再用 setter 逐一灌值，一旦以後改用建構子反序列化
     * 或欄位定義順序調整，initializer 設的預設值不保證還會生效，
     * 所以預設值邏輯明確寫在這裡而不是 DTO。
     */
    @Transactional
    public GroupCourseResponse createCourse(GroupCourseRequest request) {
        Trainer trainer = trainerRepository.findById(request.getTrainerId())
                .orElseThrow(() -> new ResourceNotFoundException("查無此教練"));

        CourseCategory category = null;
        if (request.getCourseCategoryId() != null) {
            category = courseCategoryRepository.findById(request.getCourseCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("查無此課程分類"));
        }

        GroupCourse groupCourse = new GroupCourse();
        groupCourse.setTrainer(trainer);
        groupCourse.setName(request.getName());
        groupCourse.setDescription(request.getDescription());
        groupCourse.setImageUrl(request.getImageUrl());
        groupCourse.setCategory(category);
        groupCourse.setMaxCapacity(request.getMaxCapacity());
        groupCourse.setIsTrial(request.getIsTrial() != null ? request.getIsTrial() : false);
        groupCourse.setStatus(request.getStatus() != null ? request.getStatus() : CourseStatus.ACTIVE);

        GroupCourse saved = groupCourseRepository.save(groupCourse);
        return toResponse(saved);
    }

    /** 查詢所有團課主檔，不分狀態，方便管理員也看得到已下架（非 ACTIVE）的課程。 */
    @Transactional(readOnly = true)
    public List<GroupCourseResponse> getAllCourses() {
        return groupCourseRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 下架團課主檔：把 status 改成 INACTIVE，資料不會被刪除。
     * 已經是 INACTIVE 的課程可以重複下架（冪等，不會報錯），直接回傳現有資料即可。
     * 不檢查底下是否有排定中的場次——下架只影響「之後能不能被選來建新場次」，
     * 已經排定的場次（GroupClassSession）不受影響，繼續正常進行；
     * 設計思路比照 CoachSlotController/CoachSlotService 取消時段的軟刪除
     * （DELETE 端點、底層只是狀態欄位改變，不動資料本身）。
     */
    @Transactional
    public GroupCourseResponse deactivateCourse(Integer id) {
        GroupCourse groupCourse = groupCourseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("查無此課程主檔"));

        if (groupCourse.getStatus() != CourseStatus.INACTIVE) {
            groupCourse.setStatus(CourseStatus.INACTIVE);
            groupCourse = groupCourseRepository.save(groupCourse);
        }

        return toResponse(groupCourse);
    }

    private GroupCourseResponse toResponse(GroupCourse groupCourse) {
        Trainer trainer = groupCourse.getTrainer();
        CourseCategory category = groupCourse.getCategory();
        return new GroupCourseResponse(
                groupCourse.getId(),
                groupCourse.getName(),
                trainer.getTrainerId(),
                trainer.getEmployee().getFirstName() + trainer.getEmployee().getLastName(),
                groupCourse.getDescription(),
                groupCourse.getImageUrl(),
                groupCourse.getMaxCapacity(),
                groupCourse.getIsTrial(),
                groupCourse.getStatus(),
                category != null ? category.getCourseCategoryId() : null,
                category != null ? category.getName() : null,
                groupCourse.getCreatedAt(),
                groupCourse.getUpdatedAt());
    }
}
