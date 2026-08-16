package tw.com.ispan.gym.service.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.coupon.CouponResponse;
import tw.com.ispan.gym.dto.coupon.MemberCouponResponse;
import tw.com.ispan.gym.entity.coupon.Coupon;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.enums.coupon.CouponStatus;
import tw.com.ispan.gym.enums.member.MemberCouponStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.coupon.CouponRepository;
import tw.com.ispan.gym.repository.member.MemberCouponRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 純 Mockito 單元測試（專案無內嵌測試資料庫，不用 @SpringBootTest）。
 *
 * 涵蓋：可領清單的四道過濾條件（狀態／到期／領完／已領過）、領取時同樣四道規則的
 * 逐一把關與領取數遞增、以及「已使用的券不會出現在可套用清單」。
 *
 * 到期判斷刻意用「今天」當邊界測資：endDate = 今天仍可領（isBefore(today) 為 false），
 * 昨天則不可領——這是最容易寫錯成 isAfter／!isBefore 的一行。
 *
 * 併發只驗證有沒有呼叫 findByIdForUpdate 取鎖，不模擬真正的多執行緒競爭（同 MemberRegisterServiceTest）。
 */
class MemberCouponServiceTest {

    private static final Integer MEMBER_ID = 1;
    private static final Integer COUPON_ID = 50;

    private final CouponRepository coupons = mock(CouponRepository.class);
    private final MemberCouponRepository memberCoupons = mock(MemberCouponRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final MemberCouponService service = new MemberCouponService(coupons, memberCoupons, members);

    // === 共用測試資料 ========================================================

    private Member member() {
        Member member = new Member();
        member.setId(MEMBER_ID);
        return member;
    }

    /** 預設是一張完全可領的券：ACTIVE、未到期、還有剩餘數量 */
    private Coupon coupon() {
        Coupon coupon = new Coupon();
        coupon.setCouponId(COUPON_ID);
        coupon.setCode("WELCOME100");
        coupon.setName("新會員折抵");
        coupon.setStatus(CouponStatus.ACTIVE);
        coupon.setEndDate(LocalDate.now().plusDays(7));
        coupon.setTotalQuantity(100);
        coupon.setClaimedQuantity(10);
        return coupon;
    }

    private MemberCoupon memberCoupon(Coupon coupon, MemberCouponStatus status) {
        MemberCoupon mc = new MemberCoupon();
        mc.setMember(member());
        mc.setCoupon(coupon);
        mc.setStatus(status);
        return mc;
    }

    private void stubClaimable() {
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(memberCoupons.existsByMemberIdAndCouponId(MEMBER_ID, COUPON_ID)).thenReturn(false);
        when(memberCoupons.save(any(MemberCoupon.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // === getAvailableCouponsForClaim()：四道過濾 ==============================

    @Test
    void 可領清單排除停用與已到期與領完與已領過的券() {
        Coupon ok = coupon();

        Coupon disabled = coupon();
        disabled.setCouponId(51);
        disabled.setStatus(CouponStatus.INACTIVE);

        Coupon expired = coupon();
        expired.setCouponId(52);
        expired.setEndDate(LocalDate.now().minusDays(1));

        Coupon soldOut = coupon();
        soldOut.setCouponId(53);
        soldOut.setClaimedQuantity(100); // 等於總量即為領完

        Coupon alreadyClaimed = coupon();
        alreadyClaimed.setCouponId(54);

        when(coupons.findAll()).thenReturn(List.of(ok, disabled, expired, soldOut, alreadyClaimed));
        // 其餘 couponId 沿用 Mockito 對 boolean 的預設值 false（沒領過）
        when(memberCoupons.existsByMemberIdAndCouponId(MEMBER_ID, 54)).thenReturn(true);

        List<CouponResponse> result = service.getAvailableCouponsForClaim(MEMBER_ID);

        assertThat(result).extracting(CouponResponse::getCouponId).containsExactly(COUPON_ID);
    }

    @Test
    void 無到期日的券視為永久有效可領() {
        Coupon noEndDate = coupon();
        noEndDate.setEndDate(null);
        when(coupons.findAll()).thenReturn(List.of(noEndDate));
        when(memberCoupons.existsByMemberIdAndCouponId(MEMBER_ID, COUPON_ID)).thenReturn(false);

        assertThat(service.getAvailableCouponsForClaim(MEMBER_ID)).hasSize(1);
    }

    @Test
    void 到期日為今天仍可領隔天才失效() {
        Coupon endsToday = coupon();
        endsToday.setEndDate(LocalDate.now());
        when(coupons.findAll()).thenReturn(List.of(endsToday));
        when(memberCoupons.existsByMemberIdAndCouponId(MEMBER_ID, COUPON_ID)).thenReturn(false);

        assertThat(service.getAvailableCouponsForClaim(MEMBER_ID)).hasSize(1);
    }

    // === claimCoupon()：成功路徑 ==============================================

    @Test
    void 領取成功遞增領取數並建立UNUSED的會員券() {
        Coupon coupon = coupon();
        when(coupons.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
        stubClaimable();

        MemberCouponResponse response = service.claimCoupon(MEMBER_ID, COUPON_ID);

        assertThat(response.getStatus()).isEqualTo(MemberCouponStatus.UNUSED);
        assertThat(response.getCoupon().getCouponId()).isEqualTo(COUPON_ID);
        assertThat(coupon.getClaimedQuantity()).isEqualTo(11);
        verify(coupons).save(coupon);
        // 併發防護：確實有對優惠券取鎖，序列化「最後一張」的並行搶領
        verify(coupons).findByIdForUpdate(COUPON_ID);
    }

    @Test
    void 剩最後一張可以領完後剛好額滿() {
        Coupon coupon = coupon();
        coupon.setClaimedQuantity(99); // 總量 100，剩最後一張
        when(coupons.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
        stubClaimable();

        service.claimCoupon(MEMBER_ID, COUPON_ID);

        assertThat(coupon.getClaimedQuantity()).isEqualTo(100);
    }

    // === claimCoupon()：各道關卡 ==============================================

    @Test
    void 領取時會員不存在回404且不動優惠券() {
        when(members.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.claimCoupon(MEMBER_ID, COUPON_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(coupons, never()).findByIdForUpdate(any());
        verify(memberCoupons, never()).save(any());
    }

    @Test
    void 領取時優惠券不存在回404() {
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(coupons.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.claimCoupon(MEMBER_ID, COUPON_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(memberCoupons, never()).save(any());
    }

    @Test
    void 領取已停用的券回409() {
        Coupon coupon = coupon();
        coupon.setStatus(CouponStatus.INACTIVE);
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(coupons.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));

        assertThatThrownBy(() -> service.claimCoupon(MEMBER_ID, COUPON_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("停用");

        assertThat(coupon.getClaimedQuantity()).isEqualTo(10);
        verify(memberCoupons, never()).save(any());
    }

    @Test
    void 領取已逾期的券回409() {
        Coupon coupon = coupon();
        coupon.setEndDate(LocalDate.now().minusDays(1));
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(coupons.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));

        assertThatThrownBy(() -> service.claimCoupon(MEMBER_ID, COUPON_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("逾期");

        verify(memberCoupons, never()).save(any());
    }

    @Test
    void 領取已被領完的券回409() {
        Coupon coupon = coupon();
        coupon.setClaimedQuantity(100); // 等於總量
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(coupons.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));

        assertThatThrownBy(() -> service.claimCoupon(MEMBER_ID, COUPON_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("領取一空");

        assertThat(coupon.getClaimedQuantity()).isEqualTo(100); // 沒有被多加一次
        verify(memberCoupons, never()).save(any());
    }

    @Test
    void 重複領取同一張券回409且不重複遞增領取數() {
        Coupon coupon = coupon();
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(coupons.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
        when(memberCoupons.existsByMemberIdAndCouponId(MEMBER_ID, COUPON_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.claimCoupon(MEMBER_ID, COUPON_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已經領取過");

        assertThat(coupon.getClaimedQuantity()).isEqualTo(10);
        verify(coupons, never()).save(any());
        verify(memberCoupons, never()).save(any());
    }

    // === getMyCoupons() / getMyUnusedCoupons() ================================

    @Test
    void 我的券清單原樣回傳不做過濾() {
        Coupon expired = coupon();
        expired.setEndDate(LocalDate.now().minusDays(30));
        when(memberCoupons.findByMemberId(MEMBER_ID)).thenReturn(List.of(
                memberCoupon(coupon(), MemberCouponStatus.UNUSED),
                memberCoupon(expired, MemberCouponStatus.USED)));

        // 歷史紀錄要看得到已使用與過期的券，這裡刻意不過濾
        assertThat(service.getMyCoupons(MEMBER_ID)).hasSize(2);
    }

    @Test
    void 可套用清單排除券本身已停用或已過期的() {
        Coupon usable = coupon();

        Coupon disabled = coupon();
        disabled.setCouponId(51);
        disabled.setStatus(CouponStatus.INACTIVE);

        Coupon expired = coupon();
        expired.setCouponId(52);
        expired.setEndDate(LocalDate.now().minusDays(1));

        // 查詢時已用 status=UNUSED 篩過，剩下要濾的是券本身的狀態與到期
        when(memberCoupons.findByMemberIdAndStatus(MEMBER_ID, MemberCouponStatus.UNUSED)).thenReturn(List.of(
                memberCoupon(usable, MemberCouponStatus.UNUSED),
                memberCoupon(disabled, MemberCouponStatus.UNUSED),
                memberCoupon(expired, MemberCouponStatus.UNUSED)));

        List<MemberCouponResponse> result = service.getMyUnusedCoupons(MEMBER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCoupon().getCouponId()).isEqualTo(COUPON_ID);
    }

    @Test
    void 可套用清單只查UNUSED的券() {
        when(memberCoupons.findByMemberIdAndStatus(MEMBER_ID, MemberCouponStatus.UNUSED)).thenReturn(List.of());

        assertThat(service.getMyUnusedCoupons(MEMBER_ID)).isEmpty();

        // 確認是用 UNUSED 條件下查，而不是全查回來再自己濾
        verify(memberCoupons).findByMemberIdAndStatus(MEMBER_ID, MemberCouponStatus.UNUSED);
    }
}
