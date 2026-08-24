package tw.com.ispan.gym.service.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.course.PrivateCourseRequest;
import tw.com.ispan.gym.dto.course.PrivateCourseResponse;
import tw.com.ispan.gym.dto.course.UpdatePrivateCourseRequest;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.course.CourseCategory;
import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.course.CourseCategoryRepository;
import tw.com.ispan.gym.repository.course.PrivateCourseRepository;
import tw.com.ispan.gym.service.trainer.TrainerService;

/**
 * 純 Mockito 單元測試，不連資料庫。createCourse()/deactivateByAdmin()/activateByAdmin()
 * 是 code review（2026-08-19）後新增／修改的部分：createCourse() 目前仍沒有任何
 * Controller 對外開放（見類別本身與 AdminPrivateCourseController 的註解），
 * deactivateByAdmin()/activateByAdmin() 則是為了讓「教練停用前必須清空生效中私教課」
 * 這個規則有實際可用的解法（否則 ADMIN 會被卡死，見 AdminTrainerService.suspend()）。
 */
class PrivateCourseServiceTest {

    private static final Integer TRAINER_ID = 7;
    private static final Integer COURSE_ID = 30;

    private static final Integer CATEGORY_ID = 3;

    private final PrivateCourseRepository courses = mock(PrivateCourseRepository.class);
    private final TrainerService trainerService = mock(TrainerService.class);
    private final CourseCategoryRepository categories = mock(CourseCategoryRepository.class);
    private final PrivateCourseService service = new PrivateCourseService(courses, trainerService, categories);

    private Trainer trainer() {
        Account account = new Account();
        account.setEmail("trainer@example.com");
        Employee employee = new Employee();
        employee.setFirstName("小華");
        employee.setLastName("陳");
        employee.setAccount(account);

        Trainer trainer = new Trainer();
        trainer.setId(TRAINER_ID);
        trainer.setEmployee(employee);
        return trainer;
    }

    private PrivateCourse course(CourseStatus status) {
        PrivateCourse course = new PrivateCourse();
        course.setId(COURSE_ID);
        course.setName("重量訓練體驗課");
        course.setTrainer(trainer());
        course.setStatus(status);
        course.setMaxCapacity(1);
        course.setSessionPerUnit(1);
        course.setPrice(BigDecimal.TEN);
        return course;
    }

    private PrivateCourseRequest request() {
        return new PrivateCourseRequest("重量訓練體驗課", BigDecimal.TEN, "描述", null, false,
                CourseStatus.ACTIVE, null, 1, 1, null);
    }

    private UpdatePrivateCourseRequest updateRequest() {
        return new UpdatePrivateCourseRequest("重量訓練體驗課（改版）", BigDecimal.valueOf(20), "新描述", null,
                true, 2, 1, null);
    }

    private PrivateCourseRequest requestWithCategory() {
        return new PrivateCourseRequest("重量訓練體驗課", BigDecimal.TEN, "描述", null, false,
                CourseStatus.ACTIVE, null, 1, 1, CATEGORY_ID);
    }

    private UpdatePrivateCourseRequest updateRequestWithCategory() {
        return new UpdatePrivateCourseRequest("重量訓練體驗課（改版）", BigDecimal.valueOf(20), "新描述", null,
                true, 2, 1, CATEGORY_ID);
    }

    private CourseCategory category() {
        CourseCategory category = new CourseCategory();
        category.setCourseCategoryId(CATEGORY_ID);
        category.setName("重量訓練");
        return category;
    }

    // === createCourse() ==========================================================

    @Test
    void createCourse_鎖定並檢查教練可服務狀態後才建立() {
        Trainer trainer = trainer();
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID)).thenReturn(trainer);
        when(courses.save(any(PrivateCourse.class))).thenAnswer(inv -> {
            PrivateCourse c = inv.getArgument(0);
            c.setId(COURSE_ID);
            return c;
        });

        PrivateCourseResponse response = service.createCourse(request(), TRAINER_ID);

        assertThat(response.id()).isEqualTo(COURSE_ID);
        verify(trainerService).lockAndAssertAvailableForNewBusiness(TRAINER_ID);
    }

    @Test
    void createCourse_教練非可服務狀態時擋下且不建立() {
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID))
                .thenThrow(new BusinessRuleException("教練目前無法承接新業務（非在職或非可服務狀態）"));

        assertThatThrownBy(() -> service.createCourse(request(), TRAINER_ID))
                .isInstanceOf(BusinessRuleException.class);
        verify(courses, never()).save(any());
    }

    /**
     * code review 發現（2026-08-19）：courseCategoryId 有宣告在 PrivateCourseRequest／
     * UpdatePrivateCourseRequest 上，但 applyRequest()/applyUpdateRequest() 完全沒有用到，
     * 呼叫端傳了會被靜默忽略，課程永遠不會有分類。
     */
    @Test
    void createCourse_有帶courseCategoryId時設定分類() {
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID)).thenReturn(trainer());
        when(categories.findById(CATEGORY_ID)).thenReturn(Optional.of(category()));
        when(courses.save(any(PrivateCourse.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createCourse(requestWithCategory(), TRAINER_ID);

        org.mockito.ArgumentCaptor<PrivateCourse> captor = org.mockito.ArgumentCaptor.forClass(PrivateCourse.class);
        verify(courses).save(captor.capture());
        assertThat(captor.getValue().getCategory().getCourseCategoryId()).isEqualTo(CATEGORY_ID);
    }

    @Test
    void createCourse_courseCategoryId查無分類時回404() {
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID)).thenReturn(trainer());
        when(categories.findById(CATEGORY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createCourse(requestWithCategory(), TRAINER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(courses, never()).save(any());
    }

    @Test
    void createCourse_未帶courseCategoryId時分類為null() {
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID)).thenReturn(trainer());
        when(courses.save(any(PrivateCourse.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createCourse(request(), TRAINER_ID);

        org.mockito.ArgumentCaptor<PrivateCourse> captor = org.mockito.ArgumentCaptor.forClass(PrivateCourse.class);
        verify(courses).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isNull();
        verify(categories, never()).findById(any());
    }

    // === updatePrivateCourse() / deletePrivateCourse() ============================

    @Test
    void updatePrivateCourse_非本人課程擋下() {
        PrivateCourse course = course(CourseStatus.ACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));

        assertThatThrownBy(() -> service.updatePrivateCourse(COURSE_ID, updateRequest(), 999))
                .isInstanceOf(ForbiddenAccessException.class);
        verify(courses, never()).save(any());
    }

    /**
     * code review 發現（2026-08-19）：UpdatePrivateCourseRequest 刻意不含 status 欄位——
     * 先前用共用的 PrivateCourseRequest（含 status）時，這個方法可以繞過
     * activateByAdmin() 的教練鎖定與可服務狀態檢查，直接把 status 改回 ACTIVE。
     * 這裡驗證一般欄位仍會更新，但 status 完全不受影響。
     */
    @Test
    void updatePrivateCourse_只更新一般欄位不影響status() {
        PrivateCourse course = course(CourseStatus.INACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(courses.save(any(PrivateCourse.class))).thenAnswer(inv -> inv.getArgument(0));

        PrivateCourseResponse response = service.updatePrivateCourse(COURSE_ID, updateRequest(), TRAINER_ID);

        assertThat(response.name()).isEqualTo("重量訓練體驗課（改版）");
        assertThat(response.status()).isEqualTo(CourseStatus.INACTIVE);
        verify(trainerService, never()).lockAndAssertAvailableForNewBusiness(any());
    }

    @Test
    void updatePrivateCourse_有帶courseCategoryId時更新分類() {
        PrivateCourse course = course(CourseStatus.ACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(categories.findById(CATEGORY_ID)).thenReturn(Optional.of(category()));
        when(courses.save(any(PrivateCourse.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updatePrivateCourse(COURSE_ID, updateRequestWithCategory(), TRAINER_ID);

        assertThat(course.getCategory().getCourseCategoryId()).isEqualTo(CATEGORY_ID);
    }

    @Test
    void updatePrivateCourse_courseCategoryId查無分類時回404且不儲存() {
        PrivateCourse course = course(CourseStatus.ACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(categories.findById(CATEGORY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePrivateCourse(COURSE_ID, updateRequestWithCategory(), TRAINER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(courses, never()).save(any());
    }

    /** 沒帶 courseCategoryId 時維持原分類（null-preserving），不是清空 */
    @Test
    void updatePrivateCourse_未帶courseCategoryId時維持原分類() {
        PrivateCourse course = course(CourseStatus.ACTIVE);
        course.setCategory(category());
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(courses.save(any(PrivateCourse.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updatePrivateCourse(COURSE_ID, updateRequest(), TRAINER_ID);

        assertThat(course.getCategory()).isNotNull();
        assertThat(course.getCategory().getCourseCategoryId()).isEqualTo(CATEGORY_ID);
        verify(categories, never()).findById(any());
    }

    @Test
    void deletePrivateCourse_本人課程成功下架() {
        PrivateCourse course = course(CourseStatus.ACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));

        service.deletePrivateCourse(COURSE_ID, TRAINER_ID);

        assertThat(course.getStatus()).isEqualTo(CourseStatus.INACTIVE);
    }

    @Test
    void deletePrivateCourse_非本人課程擋下() {
        PrivateCourse course = course(CourseStatus.ACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));

        assertThatThrownBy(() -> service.deletePrivateCourse(COURSE_ID, 999))
                .isInstanceOf(ForbiddenAccessException.class);
        verify(courses, never()).save(any());
    }

    // === deactivateByAdmin() ======================================================

    @Test
    void deactivateByAdmin_ACTIVE轉INACTIVE成功_不做擁有者檢查() {
        PrivateCourse course = course(CourseStatus.ACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(courses.save(any(PrivateCourse.class))).thenAnswer(inv -> inv.getArgument(0));

        PrivateCourseResponse response = service.deactivateByAdmin(COURSE_ID);

        assertThat(response.status()).isEqualTo(CourseStatus.INACTIVE);
    }

    @Test
    void deactivateByAdmin_已是INACTIVE時冪等不報錯() {
        PrivateCourse course = course(CourseStatus.INACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));

        PrivateCourseResponse response = service.deactivateByAdmin(COURSE_ID);

        assertThat(response.status()).isEqualTo(CourseStatus.INACTIVE);
        verify(courses, never()).save(any());
    }

    @Test
    void deactivateByAdmin_課程不存在時丟ResourceNotFound() {
        when(courses.findById(COURSE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivateByAdmin(COURSE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === activateByAdmin() ========================================================

    @Test
    void activateByAdmin_INACTIVE轉ACTIVE前檢查教練可服務狀態() {
        PrivateCourse course = course(CourseStatus.INACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID)).thenReturn(trainer());
        when(courses.save(any(PrivateCourse.class))).thenAnswer(inv -> inv.getArgument(0));

        PrivateCourseResponse response = service.activateByAdmin(COURSE_ID);

        assertThat(response.status()).isEqualTo(CourseStatus.ACTIVE);
        verify(trainerService).lockAndAssertAvailableForNewBusiness(TRAINER_ID);
    }

    @Test
    void activateByAdmin_教練非可服務狀態時擋下且不轉為ACTIVE() {
        PrivateCourse course = course(CourseStatus.INACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID))
                .thenThrow(new BusinessRuleException("教練目前無法承接新業務（非在職或非可服務狀態）"));

        assertThatThrownBy(() -> service.activateByAdmin(COURSE_ID))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(course.getStatus()).isEqualTo(CourseStatus.INACTIVE);
        verify(courses, never()).save(any());
    }

    @Test
    void activateByAdmin_已是ACTIVE時冪等不重新檢查教練狀態() {
        PrivateCourse course = course(CourseStatus.ACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));

        PrivateCourseResponse response = service.activateByAdmin(COURSE_ID);

        assertThat(response.status()).isEqualTo(CourseStatus.ACTIVE);
        verify(trainerService, never()).lockAndAssertAvailableForNewBusiness(any());
        verify(courses, never()).save(any());
    }

    @Test
    void activateByAdmin_課程不存在時丟ResourceNotFound() {
        when(courses.findById(COURSE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activateByAdmin(COURSE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === getCoursesByTrainerForAdmin() ============================================

    @Test
    void getCoursesByTrainerForAdmin_回傳指定教練名下所有狀態的課程() {
        when(courses.findByTrainerId(TRAINER_ID))
                .thenReturn(List.of(course(CourseStatus.ACTIVE), course(CourseStatus.INACTIVE)));

        List<PrivateCourseResponse> result = service.getCoursesByTrainerForAdmin(TRAINER_ID);

        assertThat(result).hasSize(2);
    }
}
