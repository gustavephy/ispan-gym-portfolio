package tw.com.ispan.gym.dto.coupon;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tw.com.ispan.gym.enums.CouponApplicableType;

public class CouponRequest {

    @NotBlank(message = "優惠券代碼不得為空")
    private String code;

    @NotBlank(message = "優惠券名稱不得為空")
    private String name;

    @NotBlank(message = "優惠券類型不得為空")
    private String type; // CASH 或 PERCENT

    @NotNull(message = "折抵值不得為空")
    @DecimalMin(value = "0.01", message = "折抵值必須大於 0")
    private BigDecimal value;

    @NotNull(message = "最低消費門檻不得為空")
    @DecimalMin(value = "0.00", message = "最低消費門檻不能為負數")
    private BigDecimal minSpend;

    @NotNull(message = "發放數量不得為空")
    @Min(value = 1, message = "發放數量必須大於 0")
    private Integer totalQuantity;

    private LocalDate startDate;

    private LocalDate endDate;

    private CouponApplicableType applicableType = CouponApplicableType.ALL;
}
