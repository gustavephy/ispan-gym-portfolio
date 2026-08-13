package tw.com.ispan.gym.dto.coupon;

import java.time.LocalDateTime;

import lombok.Data;
import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.enums.member.MemberCouponStatus;

@Data
public class MemberCouponResponse {
	private Integer memberCouponId;
    private CouponResponse coupon;
    private MemberCouponStatus status;
    private LocalDateTime claimedAt;
    private LocalDateTime usedAt;

    public static MemberCouponResponse from(MemberCoupon mc) {
        MemberCouponResponse dto = new MemberCouponResponse();
        dto.setMemberCouponId(mc.getMemberCouponId());
        dto.setCoupon(CouponResponse.from(mc.getCoupon()));
        dto.setStatus(mc.getStatus());
        dto.setClaimedAt(mc.getClaimedAt());
        dto.setUsedAt(mc.getUsedAt());
        return dto;
    }
}
