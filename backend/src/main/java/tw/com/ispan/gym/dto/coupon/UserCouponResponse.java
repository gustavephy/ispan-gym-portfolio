package tw.com.ispan.gym.dto.coupon;

import java.time.LocalDateTime;

import lombok.Data;
import tw.com.ispan.gym.entity.UserCoupon;
import tw.com.ispan.gym.enums.UserCouponStatus;

@Data
public class UserCouponResponse {
	private Integer id;
    private CouponResponse coupon;
    private UserCouponStatus status;
    private LocalDateTime claimedAt;
    private LocalDateTime usedAt;

    public static UserCouponResponse from(UserCoupon uc) {
        UserCouponResponse dto = new UserCouponResponse();
        dto.setId(uc.getId());
        dto.setCoupon(CouponResponse.from(uc.getCoupon()));
        dto.setStatus(uc.getStatus());
        dto.setClaimedAt(uc.getClaimedAt());
        dto.setUsedAt(uc.getUsedAt());
        return dto;
    }
}
