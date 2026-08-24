package tw.com.ispan.gym.service.coach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import tw.com.ispan.gym.dto.coach.MemberCourseQuotaResponse;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.entity.coach.MemberCourseQuota;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.repository.coach.MemberCourseQuotaRepository;

/**
 * 純 Mockito 單元測試：驗 DTO 映射與分頁參數夾限。
 *
 * <p>授權（會員只能查自己、管理端限 ADMIN）不在這層——那是 Controller 與
 * SecurityConfig 的職責，由 EndpointAuthorizationTest／AdminMemberMethodSecurityTest
 * 覆蓋；這裡的 memberId 一律視為已驗證過的可信輸入。
 */
class MemberCourseQuotaServiceTest {

    private static final Integer MEMBER_ID = 11;

    private final MemberCourseQuotaRepository repository = mock(MemberCourseQuotaRepository.class);
    private final MemberCourseQuotaService service = new MemberCourseQuotaService(repository);

    private MemberCourseQuota quota() {
        Employee employee = new Employee();
        employee.setLastName("王");
        employee.setFirstName("小美");

        Trainer trainer = new Trainer();
        trainer.setId(7);
        trainer.setEmployee(employee);

        MemberCourseQuota quota = new MemberCourseQuota();
        quota.setId(3);
        quota.setTrainer(trainer);
        quota.setCourseName("一對一重訓");
        quota.setRemainingCourse(4);
        quota.setReservedCourse(1);
        quota.setUsedCourse(5);
        quota.setExpiryDate(LocalDateTime.of(2030, 1, 1, 0, 0));
        return quota;
    }

    @Test
    void 映射_欄位完整轉換_含衍生的total與status() {
        when(repository.findByMemberId(eq(MEMBER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(quota()), PageRequest.of(0, 10), 1));

        PageResponse<MemberCourseQuotaResponse> response = service.getQuotasByMember(MEMBER_ID, 0, 10);

        assertThat(response.getItems()).hasSize(1);
        MemberCourseQuotaResponse item = response.getItems().get(0);
        assertThat(item.getId()).isEqualTo(3);
        assertThat(item.getTrainerId()).isEqualTo(7);
        // 姓在前名在後，中文姓名慣例
        assertThat(item.getTrainerName()).isEqualTo("王小美");
        assertThat(item.getCourseName()).isEqualTo("一對一重訓");
        assertThat(item.getRemainingCourse()).isEqualTo(4);
        assertThat(item.getReservedCourse()).isEqualTo(1);
        assertThat(item.getUsedCourse()).isEqualTo(5);
        // total 是 Entity 的衍生值：remaining + reserved + used
        assertThat(item.getTotal()).isEqualTo(10);
        // 到期日在未來且 remaining > 0 → ACTIVE
        assertThat(item.getStatus()).isEqualTo("ACTIVE");
        assertThat(item.getExpiryDate()).isEqualTo(LocalDateTime.of(2030, 1, 1, 0, 0));

        assertThat(response.getPage()).isZero();
        assertThat(response.getSize()).isEqualTo(10);
        assertThat(response.getTotalItems()).isEqualTo(1);
        assertThat(response.getTotalPages()).isEqualTo(1);
    }

    @Test
    void 映射_額度歸零時status為DEPLETED() {
        MemberCourseQuota depleted = quota();
        depleted.setRemainingCourse(0);
        depleted.setReservedCourse(0);
        when(repository.findByMemberId(eq(MEMBER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(depleted), PageRequest.of(0, 10), 1));

        PageResponse<MemberCourseQuotaResponse> response = service.getQuotasByMember(MEMBER_ID, 0, 10);

        assertThat(response.getItems().get(0).getStatus()).isEqualTo("DEPLETED");
    }

    @Test
    void 分頁_負數頁碼歸零_size下限為1() {
        when(repository.findByMemberId(eq(MEMBER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));

        PageResponse<MemberCourseQuotaResponse> response = service.getQuotasByMember(MEMBER_ID, -5, 0);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByMemberId(eq(MEMBER_ID), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
        // 回應中的 page/size 也要是夾限後的值，不能把原始輸入原樣回吐
        assertThat(response.getPage()).isZero();
        assertThat(response.getSize()).isEqualTo(1);
    }

    @Test
    void 分頁_size超過上限夾在100() {
        when(repository.findByMemberId(eq(MEMBER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 100), 0));

        service.getQuotasByMember(MEMBER_ID, 2, 999);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByMemberId(eq(MEMBER_ID), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void 查無資料_回空items不丟例外() {
        when(repository.findByMemberId(eq(MEMBER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        PageResponse<MemberCourseQuotaResponse> response = service.getQuotasByMember(MEMBER_ID, 0, 10);

        assertThat(response.getItems()).isEmpty();
        assertThat(response.getTotalItems()).isZero();
        assertThat(response.getTotalPages()).isZero();
    }
}
