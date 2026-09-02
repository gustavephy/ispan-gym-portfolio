package tw.com.ispan.gym.dto.order;

import jakarta.validation.constraints.Size;

/** 結帳前試算的輸入：只有選填的優惠券代碼，購物車內容由登入會員推得。 */
public record OrderPreviewRequest(@Size(max = 30) String couponCode) {}
