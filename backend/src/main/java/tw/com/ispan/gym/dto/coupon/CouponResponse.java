package tw.com.ispan.gym.dto.coupon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Data;
import tw.com.ispan.gym.entity.Coupon;
import tw.com.ispan.gym.enums.CouponApplicableType;
import tw.com.ispan.gym.enums.CouponStatus;
import tw.com.ispan.gym.enums.CouponType;

@Data
public class CouponResponse {
    private Integer id;
    private String code;
    private String name;
    private CouponType type;
    private BigDecimal value;
    private BigDecimal minSpend;
    private Integer totalQuantity;
    private Integer claimedQuantity;
    private LocalDate startDate;
    private LocalDate endDate;
    private CouponStatus status;
    private CouponApplicableType applicableType;
    private LocalDateTime createdAt;
     
    public static CouponResponse from(Coupon coupon) {
        CouponResponse dto = new CouponResponse();
        dto.setId(coupon.getId());
        dto.setCode(coupon.getCode());
        dto.setName(coupon.getName());
        dto.setType(coupon.getType());
        dto.setValue(coupon.getValue());
        dto.setMinSpend(coupon.getMinSpend());
        dto.setTotalQuantity(coupon.getTotalQuantity());
        dto.setClaimedQuantity(coupon.getClaimedQuantity());
        dto.setStartDate(coupon.getStartDate());
        dto.setEndDate(coupon.getEndDate());
        dto.setStatus(coupon.getStatus());
        dto.setApplicableType(coupon.getApplicableType());
        dto.setCreatedAt(coupon.getCreatedAt());
        return dto;
    }
}
