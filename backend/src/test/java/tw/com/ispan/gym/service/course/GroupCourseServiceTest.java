package tw.com.ispan.gym.service.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.course.GroupCourseRequest;
import tw.com.ispan.gym.dto.course.GroupCourseResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.course.CourseCategoryRepository;
import tw.com.ispan.gym.repository.course.GroupCourseRepository;
import tw.com.ispan.gym.service.trainer.TrainerService;

/**
 * 純 Mockito 單元測試，不連資料庫。只針對 code review（2026-08-19）發現的部分補測試：
 * createCourse()/activateCourse() 先前完全沒有檢查教練可服務狀態，能替 UNAVAILABLE
 * 或已軟刪除的教練建立／重新上架 ACTIVE 團課主檔，跟原規格§9「建立或指派 GroupClass」
 * 不一致。其餘既有方法（getAllCourses／deactivateCourse／updateCourse／
 * getActiveCoursesPaged）維持原樣，不在這次補測範圍。
 */
class GroupCourseServiceTest {

    private static final Integer COURSE_ID = 30;
    private static final Integer TRAINER_ID = 7;

    private final GroupCourseRepository courses = mock(GroupCourseRepository.class);
    private final TrainerService trainerService = mock(TrainerService.class);
    private final CourseCategoryRepository categories = mock(CourseCategoryRepository.class);
    private final GroupCourseService service = new GroupCourseService(courses, trainerService, categories);

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

    private GroupCourse course(CourseStatus status) {
        GroupCourse course = new GroupCourse();
        course.setId(COURSE_ID);
        course.setName("戰鬥有氧");
        course.setMaxCapacity(15);
        course.setTrainer(trainer());
        course.setStatus(status);
        return course;
    }

    private GroupCourseRequest request() {
        GroupCourseRequest request = new GroupCourseRequest();
        request.setName("戰鬥有氧");
        request.setTrainerId(TRAINER_ID);
        request.setMaxCapacity(15);
        return request;
    }

    // === createCourse() ===========================================================

    @Test
    void createCourse_鎖定並檢查教練可服務狀態後才建立() {
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID)).thenReturn(trainer());
        when(courses.save(any(GroupCourse.class))).thenAnswer(inv -> {
            GroupCourse c = inv.getArgument(0);
            c.setId(COURSE_ID);
            return c;
        });

        GroupCourseResponse response = service.createCourse(request());

        assertThat(response.getId()).isEqualTo(COURSE_ID);
        verify(trainerService).lockAndAssertAvailableForNewBusiness(TRAINER_ID);
    }

    @Test
    void createCourse_教練非可服務狀態時擋下且不建立() {
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID))
                .thenThrow(new BusinessRuleException("教練目前無法承接新業務（非在職或非可服務狀態）"));

        assertThatThrownBy(() -> service.createCourse(request()))
                .isInstanceOf(BusinessRuleException.class);
        verify(courses, never()).save(any());
    }

    @Test
    void createCourse_教練不存在時回404() {
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID))
                .thenThrow(new ResourceNotFoundException("找不到此教練"));

        assertThatThrownBy(() -> service.createCourse(request()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(courses, never()).save(any());
    }

    // === activateCourse() =========================================================

    @Test
    void activateCourse_INACTIVE轉ACTIVE前檢查教練可服務狀態() {
        GroupCourse course = course(CourseStatus.INACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID)).thenReturn(trainer());
        when(courses.save(any(GroupCourse.class))).thenAnswer(inv -> inv.getArgument(0));

        GroupCourseResponse response = service.activateCourse(COURSE_ID);

        assertThat(response.getStatus()).isEqualTo(CourseStatus.ACTIVE);
        verify(trainerService).lockAndAssertAvailableForNewBusiness(TRAINER_ID);
    }

    @Test
    void activateCourse_教練非可服務狀態時擋下且不轉為ACTIVE() {
        GroupCourse course = course(CourseStatus.INACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID))
                .thenThrow(new BusinessRuleException("教練目前無法承接新業務（非在職或非可服務狀態）"));

        assertThatThrownBy(() -> service.activateCourse(COURSE_ID))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(course.getStatus()).isEqualTo(CourseStatus.INACTIVE);
        verify(courses, never()).save(any());
    }

    @Test
    void activateCourse_已是ACTIVE時冪等不重新檢查教練狀態() {
        GroupCourse course = course(CourseStatus.ACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));

        GroupCourseResponse response = service.activateCourse(COURSE_ID);

        assertThat(response.getStatus()).isEqualTo(CourseStatus.ACTIVE);
        verify(trainerService, never()).lockAndAssertAvailableForNewBusiness(any());
        verify(courses, never()).save(any());
    }

    @Test
    void updateCourse_可以成功更新教練() {
        GroupCourse course = course(CourseStatus.ACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        
        tw.com.ispan.gym.dto.course.UpdateGroupCourseRequest updateReq = new tw.com.ispan.gym.dto.course.UpdateGroupCourseRequest();
        updateReq.setName("新課程名稱");
        updateReq.setMaxCapacity(20);
        updateReq.setTrainerId(99); // new trainer
        
        Trainer newTrainer = new Trainer();
        newTrainer.setId(99);
        Employee employee = new Employee();
        employee.setFirstName("大同");
        employee.setLastName("王");
        Account account = new Account();
        account.setEmail("wang@example.com");
        employee.setAccount(account);
        newTrainer.setEmployee(employee);
        
        when(trainerService.lockAndAssertAvailableForNewBusiness(99)).thenReturn(newTrainer);
        when(courses.save(any(GroupCourse.class))).thenAnswer(inv -> inv.getArgument(0));
        
        GroupCourseResponse response = service.updateCourse(COURSE_ID, updateReq);
        
        assertThat(response.getName()).isEqualTo("新課程名稱");
        assertThat(response.getTrainerId()).isEqualTo(99);
        verify(trainerService).lockAndAssertAvailableForNewBusiness(99);
    }
}
