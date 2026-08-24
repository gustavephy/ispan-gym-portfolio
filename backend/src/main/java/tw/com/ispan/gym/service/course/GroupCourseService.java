package tw.com.ispan.gym.service.course;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.dto.course.GroupCourseRequest;
import tw.com.ispan.gym.dto.course.GroupCourseResponse;
import tw.com.ispan.gym.dto.course.UpdateGroupCourseRequest;
import tw.com.ispan.gym.entity.course.CourseCategory;
import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.course.CourseCategoryRepository;
import tw.com.ispan.gym.repository.course.GroupCourseRepository;
import tw.com.ispan.gym.service.trainer.TrainerService;

/**
 * 管理員維護團課主檔（GroupCourse）的商業邏輯：建立與查詢。
 * 場次（GroupClassSession）相關邏輯在 {@code GroupClassService}，職責分開——
 * 這裡只管「課程本身長怎樣」（教練、名稱、容量……），不涉及任何特定場次的時間、教室、報名狀態。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupCourseService {

    private final GroupCourseRepository groupCourseRepository;
    private final TrainerService trainerService;
    private final CourseCategoryRepository courseCategoryRepository;

    // ▶ 前台呼叫：GET /api/group-courses/public（CourseView 團課介紹頁、商城商品頁）
    // 取得前台商城展示中的團體課程方案列表 (不分頁)
    public List<GroupCourseResponse> getPublicCourses(
            String keyword, Integer trainerId, Integer categoryId, LocalDate startDate, LocalDate endDate) {
        return getAllCourses(keyword, trainerId, categoryId, CourseStatus.ACTIVE, startDate, endDate, 0, 9999)
                .getContent();
    }

    // ▶ 前台 getPublicCourses 內部呼叫、後台 AdminGroupCourseController
    // 取得團體課程清單（分頁，員工/後台管理用）
    public Page<GroupCourseResponse> getAllCourses(
            String keyword,
            Integer trainerId,
            Integer categoryId,
            CourseStatus status,
            LocalDate startDate,
            LocalDate endDate,
            int page,
            int pageSize) {
        PageRequest pageRequest = PageRequest.of(page, pageSize, Sort.by("groupCourseId").descending());

        LocalDateTime startLdt = null;
        if (startDate != null) {
            startLdt = startDate.atStartOfDay();
        }

        LocalDateTime endLdt = null;
        if (endDate != null) {
            endLdt = endDate.atTime(23, 59, 59, 999999);
        }

        Page<GroupCourse> courses = groupCourseRepository.findCoursesWithFilters(
                status,
                trainerId,
                (keyword != null && !keyword.isBlank()) ? keyword.trim() : null,
                startLdt,
                endLdt,
                pageRequest);
        return courses.map(GroupCourseResponse::from);
    }

    // 建立團課主檔。
    // isTrial／status 允許前端不填，這裡補上預設値（false／ACTIVE）——刻意不放在
    // DTO 欄位的 initializer：Lombok @Data 產生的物件在 JSON 反序列化時，
    // Jackson 是先建構空物件再用 setter 逐一灣値，一旦以後改用建構子反序列化
    // 或欄位定義順序調整，initializer 設的預設値不保證還會生效，
    // 所以預設値邏輯明確寫在這裡而不是 DTO。
    // §9 跨模組防護：UNAVAILABLE／已軟刪除的教練不可被指派新的團課主檔（原規格§9
    // 「建立或指派 GroupClass」本來就明列這一項，先前遙漏，code review 發現，
    // 2026-08-19）。改用 trainerService.lockAndAssertAvailableForNewBusiness() 一次
    // 完成「查無此教練」的 404 與可服務狀態的 409，不再直接用 trainerRepository.findById()。
    // ▶ 後台呼叫：AdminGroupCourseController POST /admin/group-courses
    @Transactional
    public GroupCourseResponse createCourse(GroupCourseRequest request) {
        Trainer trainer = trainerService.lockAndAssertAvailableForNewBusiness(request.getTrainerId());

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
        return GroupCourseResponse.from(saved);
    }

    // 後台團課主檔列表，分頁＋依課程名稱或授課教練姓名搜尋，不分狀態，方便管理員
    // 也看得到已下架（非 ACTIVE）的課程。
    // size 做安全上限（1~100）避免有人傳誇張的値一次撈爆資料庫；page 不可小於 0。
    // 排序固定依 createdAt 新到舊，寫在這裡組出的 Pageable 裡，不寫在 JPQL
    // （理由見 GroupCourseRepository#searchCourses）。
    // ▶ 後台呼叫：AdminGroupCourseController GET /admin/group-courses（分頁搜尋）
    @Transactional(readOnly = true)
    public PageResponse<GroupCourseResponse> getAllCourses(int page, int size, String search) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);

        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<GroupCourse> result = groupCourseRepository.searchCourses(search, pageable);

        List<GroupCourseResponse> items = result.getContent().stream()
                .map(GroupCourseResponse::from)
                .toList();

        return new PageResponse<>(items, safePage, safeSize, result.getTotalElements(), result.getTotalPages());
    }

    // 下架團課主檔：把 status 改成 INACTIVE，資料不會被刪除。
    // 已經是 INACTIVE 的課程可以重複下架（冪等，不會報錯），直接回傳現有資料。
    // 不檢查底下是否有排定中的場次——下架只影響「之後能不能被選來建新場次」，
    // 已經排定的場次（GroupClassSession）不受影響，繼續正常進行；
    // 設計思路比照 CoachSlotController/CoachSlotService 取消時段的軟刪除
    // （DELETE 端點、底層只是狀態欄位改變，不動資料本身）。
    // ▶ 後台呼叫：AdminGroupCourseController DELETE /admin/group-courses/{id}
    @Transactional
    public GroupCourseResponse deactivateCourse(Integer id) {
        GroupCourse groupCourse = groupCourseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("查無此課程主檔"));

        if (groupCourse.getStatus() != CourseStatus.INACTIVE) {
            groupCourse.setStatus(CourseStatus.INACTIVE);
            groupCourse = groupCourseRepository.save(groupCourse);
        }

        return GroupCourseResponse.from(groupCourse);
    }

    // 重新上架已下架的團課主檔：把 status 改回 ACTIVE。與 deactivateCourse() 對稱，
    // 已經是 ACTIVE 的課程可以重複呼叫（冪等，不會報錯），直接回傳現有資料。
    // §9 跨模組防護：實際觸發狀態轉換時才檢查教練可服務狀態，跟 createCourse() 現在
    // 用同一套標準。已是 ACTIVE 時視為 no-op，不重新檢查。
    // 2026-08-19，改成兩層都查，不是互斥選項。
    // ▶ 後台呼叫：AdminGroupCourseController PUT /admin/group-courses/{id}/activate
    @Transactional
    public GroupCourseResponse activateCourse(Integer id) {
        GroupCourse groupCourse = groupCourseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("查無此課程主檔"));

        if (groupCourse.getStatus() != CourseStatus.ACTIVE) {
            trainerService.lockAndAssertAvailableForNewBusiness(groupCourse.getTrainer().getTrainerId());
            groupCourse.setStatus(CourseStatus.ACTIVE);
            groupCourse = groupCourseRepository.save(groupCourse);
        }

        return GroupCourseResponse.from(groupCourse);
    }

    // 修改團課主檔。name、maxCapacity 為必填，全量覆蓋；description、imageUrl、isTrial、
    // courseCategoryId 允許不填，request 對應欄位為 null 時維持 Entity 現有値，不會被清空。
    // 不接受變更 trainerId／status：授課教練換人需另開課程，狀態只走既有的下架端點。
    // ▶ 後台呼叫：AdminGroupCourseController PUT /admin/group-courses/{id}
    @Transactional
    public GroupCourseResponse updateCourse(Integer id, UpdateGroupCourseRequest request) {
        GroupCourse groupCourse = groupCourseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("查無此課程主檔"));

        groupCourse.setName(request.getName());
        groupCourse.setMaxCapacity(request.getMaxCapacity());

        if (request.getDescription() != null) {
            groupCourse.setDescription(request.getDescription());
        }
        if (request.getImageUrl() != null) {
            groupCourse.setImageUrl(request.getImageUrl());
        }
        if (request.getIsTrial() != null) {
            groupCourse.setIsTrial(request.getIsTrial());
        }
        if (request.getCourseCategoryId() != null) {
            CourseCategory category = courseCategoryRepository.findById(request.getCourseCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("查無此課程分類"));
            groupCourse.setCategory(category);
        }

        GroupCourse saved = groupCourseRepository.save(groupCourse);
        return GroupCourseResponse.from(saved);
    }

}

