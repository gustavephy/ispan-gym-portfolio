package tw.com.ispan.gym.dto.coupon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.coupon.Coupon;
import tw.com.ispan.gym.enums.coupon.CouponApplicableType;
import tw.com.ispan.gym.enums.coupon.CouponStatus;
import tw.com.ispan.gym.enums.coupon.CouponType;

public record CouponResponse(
    Integer couponId,
    String code,
    String name,
    CouponType type,
    BigDecimal value,
    BigDecimal minSpend,
    Integer totalQuantity,
    Integer claimedQuantity,
    LocalDate startDate,
    LocalDate endDate,
    CouponStatus status,
    CouponApplicableType applicableType,
    LocalDateTime createdAt,
    String description
) {
    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(
            coupon.getCouponId(),
            coupon.getCode(),
            coupon.getName(),
            coupon.getType(),
            coupon.getValue(),
            coupon.getMinSpend(),
            coupon.getTotalQuantity(),
            coupon.getClaimedQuantity(),
            coupon.getStartDate(),
            coupon.getEndDate(),
            coupon.getStatus(),
            coupon.getApplicableType(),
            coupon.getCreatedAt(),
            coupon.getDescription()
        );
    }
}
