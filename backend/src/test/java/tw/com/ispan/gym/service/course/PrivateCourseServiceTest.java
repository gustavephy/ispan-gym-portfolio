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
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.course.PrivateCourseRepository;
import tw.com.ispan.gym.service.trainer.TrainerService;

class PrivateCourseServiceTest {

    private static final Integer TRAINER_ID = 7;
    private static final Integer COURSE_ID = 30;

    private final PrivateCourseRepository courses = mock(PrivateCourseRepository.class);
    private final TrainerService trainerService = mock(TrainerService.class);
    private final PrivateCourseService service = new PrivateCourseService(courses, trainerService);

    private Trainer trainer() {
        Account account = new Account();
        account.setEmail("trainer@example.com");
        Employee employee = new Employee();
        employee.setFirstName("小華");
        employee.setLastName("陳");
        employee.setAccount(account);

        Trainer trainer = new Trainer();
        trainer.setTrainerId(TRAINER_ID);
        trainer.setEmployee(employee);
        return trainer;
    }

    private PrivateCourse course(CourseStatus status) {
        PrivateCourse course = new PrivateCourse();
        course.setPrivateCourseId(COURSE_ID);
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

    // === createCourse() ==========================================================

    @Test
    void createCourse_成功建立() {
        Trainer trainer = trainer();
        when(courses.save(any(PrivateCourse.class))).thenAnswer(inv -> {
            PrivateCourse c = inv.getArgument(0);
            c.setPrivateCourseId(COURSE_ID);
            return c;
        });

        PrivateCourseResponse response = service.createCourse(request(), trainer);

        assertThat(response.id()).isEqualTo(COURSE_ID);
        verify(courses).save(any(PrivateCourse.class));
    }

    // === updateCourse() / deleteCourse() ==========================================

    @Test
    void updateCourse_非本人課程擋下() {
        PrivateCourse course = course(CourseStatus.ACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));

        Trainer anotherTrainer = new Trainer();
        anotherTrainer.setTrainerId(999);

        assertThatThrownBy(() -> service.updateCourse(COURSE_ID, request(), anotherTrainer))
                .isInstanceOf(ForbiddenAccessException.class);
        verify(courses, never()).save(any());
    }

    @Test
    void updateCourse_本人課程成功更新() {
        PrivateCourse course = course(CourseStatus.ACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(courses.save(any(PrivateCourse.class))).thenAnswer(inv -> inv.getArgument(0));

        PrivateCourseResponse response = service.updateCourse(COURSE_ID, request(), trainer());

        assertThat(response.name()).isEqualTo("重量訓練體驗課");
        verify(courses).save(any(PrivateCourse.class));
    }

    @Test
    void deleteCourse_本人課程成功下架() {
        PrivateCourse course = course(CourseStatus.ACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));

        service.deleteCourse(COURSE_ID, trainer());

        assertThat(course.getStatus()).isEqualTo(CourseStatus.INACTIVE);
        verify(courses).save(course);
    }

    @Test
    void deleteCourse_非本人課程擋下() {
        PrivateCourse course = course(CourseStatus.ACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));

        Trainer anotherTrainer = new Trainer();
        anotherTrainer.setTrainerId(999);

        assertThatThrownBy(() -> service.deleteCourse(COURSE_ID, anotherTrainer))
                .isInstanceOf(ForbiddenAccessException.class);
        verify(courses, never()).save(any());
    }

    // === deactivateByAdmin() ======================================================

    @Test
    void deactivateByAdmin_正常下架() {
        PrivateCourse course = course(CourseStatus.ACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(courses.save(any(PrivateCourse.class))).thenAnswer(inv -> inv.getArgument(0));

        PrivateCourseResponse response = service.deactivateByAdmin(COURSE_ID);

        assertThat(response.status()).isEqualTo(CourseStatus.INACTIVE);
    }

    // === activateByAdmin() ========================================================

    @Test
    void activateByAdmin_重新上架前檢查教練可服務狀態() {
        PrivateCourse course = course(CourseStatus.INACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID)).thenReturn(trainer());
        when(courses.save(any(PrivateCourse.class))).thenAnswer(inv -> inv.getArgument(0));

        PrivateCourseResponse response = service.activateByAdmin(COURSE_ID);

        assertThat(response.status()).isEqualTo(CourseStatus.ACTIVE);
        verify(trainerService).lockAndAssertAvailableForNewBusiness(TRAINER_ID);
    }

    @Test
    void activateByAdmin_教練非可服務狀態時擋下() {
        PrivateCourse course = course(CourseStatus.INACTIVE);
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID))
                .thenThrow(new BusinessRuleException("教練目前無法承接新業務"));

        assertThatThrownBy(() -> service.activateByAdmin(COURSE_ID))
                .isInstanceOf(BusinessRuleException.class);
        verify(courses, never()).save(any());
    }
}
