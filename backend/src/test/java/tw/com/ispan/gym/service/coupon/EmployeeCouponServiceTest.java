package tw.com.ispan.gym.service.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.coupon.CouponRequest;
import tw.com.ispan.gym.dto.coupon.CouponResponse;
import tw.com.ispan.gym.dto.coupon.CouponStatsResponse;
import tw.com.ispan.gym.entity.coupon.Coupon;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.enums.coupon.CouponApplicableType;
import tw.com.ispan.gym.enums.coupon.CouponStatus;
import tw.com.ispan.gym.enums.coupon.CouponType;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.coupon.CouponRepository;
import tw.com.ispan.gym.repository.member.MemberCouponRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 純 Mockito 單元測試（專案無內嵌測試資料庫，不用 @SpringBootTest）。
 *
 * 涵蓋：代碼唯一性（建立與更新時的自我排除）、代碼正規化為大寫、關鍵字／狀態／起始日
 * 三種篩選、統計的分類邏輯，以及群發／指定發放時「跳過已持有者」與「發放量可撐大總量」的行為。
 *
 * 「下架＝改 INACTIVE 而非刪除」與商品端一致，這裡一併驗證。
 */
class EmployeeCouponServiceTest {

    private static final Integer COUPON_ID = 50;

    private final CouponRepository coupons = mock(CouponRepository.class);
    private final MemberCouponRepository memberCoupons = mock(MemberCouponRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final EmployeeCouponService service = new EmployeeCouponService(coupons, memberCoupons, members);

    // === 共用測試資料 ========================================================

    private CouponRequest request(String code) {
        CouponRequest request = new CouponRequest();
        request.setCode(code);
        request.setName("新春優惠");
        request.setType(CouponType.CASH);
        request.setValue(new BigDecimal("100"));
        request.setMinSpend(new BigDecimal("500"));
        request.setTotalQuantity(100);
        request.setStartDate(LocalDate.now().minusDays(1));
        request.setEndDate(LocalDate.now().plusDays(30));
        return request;
    }

    private Coupon coupon(Integer id, String code, CouponStatus status) {
        Coupon coupon = new Coupon();
        coupon.setCouponId(id);
        coupon.setCode(code);
        coupon.setName("測試券");
        coupon.setStatus(status);
        coupon.setStartDate(LocalDate.now().minusDays(1));
        coupon.setEndDate(LocalDate.now().plusDays(7));
        coupon.setTotalQuantity(100);
        coupon.setClaimedQuantity(0);
        return coupon;
    }

    private Member member(Integer id, UserStatus status) {
        Member member = new Member();
        member.setId(id);
        member.setStatus(status);
        return member;
    }

    // === createCoupon() ======================================================

    @Test
    void 建立優惠券把代碼正規化為大寫並去除空白() {
        when(coupons.findByCode(any())).thenReturn(Optional.empty());
        when(coupons.save(any(Coupon.class))).thenAnswer(inv -> inv.getArgument(0));

        CouponResponse response = service.createCoupon(request("  spring20  "));

        assertThat(response.getCode()).isEqualTo("SPRING20");
    }

    @Test
    void 建立優惠券時代碼重複擋下() {
        when(coupons.findByCode("SPRING20")).thenReturn(Optional.of(coupon(COUPON_ID, "SPRING20", CouponStatus.ACTIVE)));

        assertThatThrownBy(() -> service.createCoupon(request("SPRING20")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已存在");

        verify(coupons, never()).save(any());
    }

    @Test
    void 建立優惠券未指定適用類型時預設為全品項() {
        when(coupons.findByCode(any())).thenReturn(Optional.empty());
        when(coupons.save(any(Coupon.class))).thenAnswer(inv -> inv.getArgument(0));
        CouponRequest request = request("NEW1");
        request.setApplicableType(null);

        CouponResponse response = service.createCoupon(request);

        assertThat(response.getApplicableType()).isEqualTo(CouponApplicableType.ALL);
    }

    // === updateCoupon() ======================================================

    @Test
    void 更新優惠券沿用自己的代碼不會被誤判為重複() {
        Coupon existing = coupon(COUPON_ID, "SPRING20", CouponStatus.ACTIVE);
        when(coupons.findById(COUPON_ID)).thenReturn(Optional.of(existing));
        // 查到的就是自己
        when(coupons.findByCode("SPRING20")).thenReturn(Optional.of(existing));
        when(coupons.save(any(Coupon.class))).thenAnswer(inv -> inv.getArgument(0));

        CouponResponse response = service.updateCoupon(COUPON_ID, request("SPRING20"));

        assertThat(response.getCode()).isEqualTo("SPRING20");
    }

    @Test
    void 更新優惠券撞到別張券的代碼擋下() {
        Coupon existing = coupon(COUPON_ID, "SPRING20", CouponStatus.ACTIVE);
        Coupon another = coupon(99, "SUMMER30", CouponStatus.ACTIVE);
        when(coupons.findById(COUPON_ID)).thenReturn(Optional.of(existing));
        when(coupons.findByCode("SUMMER30")).thenReturn(Optional.of(another));

        assertThatThrownBy(() -> service.updateCoupon(COUPON_ID, request("SUMMER30")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void 更新不存在的優惠券回404() {
        when(coupons.findById(COUPON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCoupon(COUPON_ID, request("ANY")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === deleteCoupon()：軟下架 ===============================================

    @Test
    void 下架優惠券只改狀態不實體刪除() {
        Coupon existing = coupon(COUPON_ID, "SPRING20", CouponStatus.ACTIVE);
        when(coupons.findById(COUPON_ID)).thenReturn(Optional.of(existing));

        service.deleteCoupon(COUPON_ID);

        assertThat(existing.getStatus()).isEqualTo(CouponStatus.INACTIVE);
        verify(coupons, never()).delete(any());
    }

    // === getAllCoupons()：篩選 ================================================

    @Test
    void 關鍵字可比對代碼或名稱且不分大小寫() {
        Coupon byCode = coupon(1, "SPRING20", CouponStatus.ACTIVE);
        Coupon byName = coupon(2, "XMAS", CouponStatus.ACTIVE);
        byName.setName("Spring 限定");
        Coupon unrelated = coupon(3, "SUMMER30", CouponStatus.ACTIVE);
        unrelated.setName("夏季優惠");
        when(coupons.findAll()).thenReturn(List.of(byCode, byName, unrelated));

        List<CouponResponse> result = service.getAllCoupons("spring", null, null, null);

        assertThat(result).extracting(CouponResponse::getCouponId).containsExactly(1, 2);
    }

    @Test
    void 沒給關鍵字時不做關鍵字過濾() {
        when(coupons.findAll()).thenReturn(List.of(
                coupon(1, "A", CouponStatus.ACTIVE), coupon(2, "B", CouponStatus.ACTIVE)));

        assertThat(service.getAllCoupons(null, null, null, null)).hasSize(2);
        assertThat(service.getAllCoupons("   ", null, null, null)).hasSize(2);
    }

    @Test
    void 起始日篩選只留開始日不早於指定日期的券() {
        Coupon early = coupon(1, "EARLY", CouponStatus.ACTIVE);
        early.setStartDate(LocalDate.now().minusDays(10));
        Coupon late = coupon(2, "LATE", CouponStatus.ACTIVE);
        late.setStartDate(LocalDate.now().plusDays(10));
        when(coupons.findAll()).thenReturn(List.of(early, late));

        List<CouponResponse> result = service.getAllCoupons(null, null, LocalDate.now(), null);

        assertThat(result).extracting(CouponResponse::getCouponId).containsExactly(2);
    }

    @Test
    void 狀態篩選支援進行中與未開始與已結束() {
        Coupon ongoing = coupon(1, "ONGOING", CouponStatus.ACTIVE);
        Coupon upcoming = coupon(2, "UPCOMING", CouponStatus.ACTIVE);
        upcoming.setStartDate(LocalDate.now().plusDays(3));
        Coupon expired = coupon(3, "EXPIRED", CouponStatus.ACTIVE);
        expired.setEndDate(LocalDate.now().minusDays(1));
        when(coupons.findAll()).thenReturn(List.of(ongoing, upcoming, expired));

        assertThat(service.getAllCoupons(null, "ONGOING", null, null))
                .extracting(CouponResponse::getCouponId).containsExactly(1);
        assertThat(service.getAllCoupons(null, "upcoming", null, null))
                .extracting(CouponResponse::getCouponId).containsExactly(2);
        assertThat(service.getAllCoupons(null, "EXPIRED", null, null))
                .extracting(CouponResponse::getCouponId).containsExactly(3);
    }

    @Test
    void 無法辨識的狀態字串視為不過濾而不是拋錯() {
        when(coupons.findAll()).thenReturn(List.of(
                coupon(1, "A", CouponStatus.ACTIVE), coupon(2, "B", CouponStatus.INACTIVE)));

        assertThat(service.getAllCoupons(null, "NOT_A_STATUS", null, null)).hasSize(2);
    }

    // === getCouponStats() ====================================================

    @Test
    void 統計分別計算進行中未開始已結束與累計領取數() {
        Coupon ongoing = coupon(1, "A", CouponStatus.ACTIVE);
        ongoing.setClaimedQuantity(5);

        Coupon upcoming = coupon(2, "B", CouponStatus.ACTIVE);
        upcoming.setStartDate(LocalDate.now().plusDays(3));
        upcoming.setClaimedQuantity(0);

        Coupon expiredByDate = coupon(3, "C", CouponStatus.ACTIVE);
        expiredByDate.setEndDate(LocalDate.now().minusDays(1));
        expiredByDate.setClaimedQuantity(7);

        Coupon disabled = coupon(4, "D", CouponStatus.INACTIVE);
        disabled.setClaimedQuantity(3);

        when(coupons.findAll()).thenReturn(List.of(ongoing, upcoming, expiredByDate, disabled));

        CouponStatsResponse stats = service.getCouponStats();

        assertThat(stats.getActiveCount()).isEqualTo(1);
        assertThat(stats.getUpcomingCount()).isEqualTo(1);
        assertThat(stats.getExpiredCount()).isEqualTo(2); // 過期的與停用的
        assertThat(stats.getTotalClaimedCount()).isEqualTo(15);
    }

    @Test
    void 統計時無起訖日的券視為即日起長期有效() {
        Coupon noDates = coupon(1, "FOREVER", CouponStatus.ACTIVE);
        noDates.setStartDate(null);
        noDates.setEndDate(null);
        when(coupons.findAll()).thenReturn(List.of(noDates));

        CouponStatsResponse stats = service.getCouponStats();

        assertThat(stats.getActiveCount()).isEqualTo(1);
        assertThat(stats.getExpiredCount()).isZero();
    }

    // === distributeCouponToAllMembers() =======================================

    @Test
    void 群發只發給啟用中的會員並跳過已持有者() {
        Coupon coupon = coupon(COUPON_ID, "GIFT", CouponStatus.ACTIVE);
        when(coupons.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
        when(members.findAll()).thenReturn(List.of(
                member(1, UserStatus.ACTIVE),
                member(2, UserStatus.ACTIVE),
                member(3, UserStatus.SUSPENDED),  // 停權，不發
                member(4, UserStatus.PENDING)));  // 未驗證，不發
        // 會員 2 已經有這張券
        when(memberCoupons.existsByMemberIdAndCouponId(2, COUPON_ID)).thenReturn(true);

        int count = service.distributeCouponToAllMembers(COUPON_ID);

        assertThat(count).isEqualTo(1);
        assertThat(coupon.getClaimedQuantity()).isEqualTo(1);
        verify(memberCoupons, times(1)).save(any(MemberCoupon.class));
    }

    @Test
    void 群發時已停用的優惠券擋下() {
        when(coupons.findById(COUPON_ID)).thenReturn(Optional.of(coupon(COUPON_ID, "GIFT", CouponStatus.INACTIVE)));

        assertThatThrownBy(() -> service.distributeCouponToAllMembers(COUPON_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("無法發放");

        verify(memberCoupons, never()).save(any());
    }

    @Test
    void 群發超過原總量時自動把總量撐大以維持發放數不超標() {
        Coupon coupon = coupon(COUPON_ID, "GIFT", CouponStatus.ACTIVE);
        coupon.setTotalQuantity(1); // 只準備 1 張，但有 3 位啟用會員
        when(coupons.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
        when(members.findAll()).thenReturn(List.of(
                member(1, UserStatus.ACTIVE), member(2, UserStatus.ACTIVE), member(3, UserStatus.ACTIVE)));

        int count = service.distributeCouponToAllMembers(COUPON_ID);

        assertThat(count).isEqualTo(3);
        assertThat(coupon.getClaimedQuantity()).isEqualTo(3);
        // 總量被撐大到與實際發放數一致，不會出現 claimed > total 的不一致狀態
        assertThat(coupon.getTotalQuantity()).isEqualTo(3);
    }

    @Test
    void 群發優惠券不存在回404() {
        when(coupons.findById(COUPON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.distributeCouponToAllMembers(COUPON_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === distributeToMembers() ==================================================

    // 2026-08-25 admin-ui-standardization 之後語義改變：名單有問題不再靜默跳過，
    // 而是整批擋下（@Transactional 回滾），讓管理者知道名單有誤而不是默默少發幾張

    @Test
    void 指定發放名單含不存在的會員時整批擋下() {
        Coupon coupon = coupon(COUPON_ID, "GIFT", CouponStatus.ACTIVE);
        when(coupons.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
        when(members.findById(1)).thenReturn(Optional.of(member(1, UserStatus.ACTIVE)));
        when(members.findById(2)).thenReturn(Optional.empty()); // 不存在

        assertThatThrownBy(() -> service.distributeToMembers(COUPON_ID, List.of(1, 2)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 指定發放名單含已持有者時整批擋下() {
        Coupon coupon = coupon(COUPON_ID, "GIFT", CouponStatus.ACTIVE);
        when(coupons.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
        when(members.findById(1)).thenReturn(Optional.of(member(1, UserStatus.ACTIVE)));
        when(memberCoupons.existsByMemberIdAndCouponId(1, COUPON_ID)).thenReturn(true); // 已持有

        assertThatThrownBy(() -> service.distributeToMembers(COUPON_ID, List.of(1)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void 指定發放時已停用的優惠券擋下() {
        when(coupons.findById(COUPON_ID)).thenReturn(Optional.of(coupon(COUPON_ID, "GIFT", CouponStatus.INACTIVE)));

        assertThatThrownBy(() -> service.distributeToMembers(COUPON_ID, List.of(1)))
                .isInstanceOf(BusinessRuleException.class);

        verify(memberCoupons, never()).save(any());
    }

    @Test
    void 指定發放空名單回零不報錯() {
        Coupon coupon = coupon(COUPON_ID, "GIFT", CouponStatus.ACTIVE);
        when(coupons.findById(COUPON_ID)).thenReturn(Optional.of(coupon));

        assertThat(service.distributeToMembers(COUPON_ID, List.of())).isZero();
        assertThat(coupon.getClaimedQuantity()).isZero();
    }
}
