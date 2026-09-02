package tw.com.ispan.gym.dto.order;

import java.math.BigDecimal;

/**
 * 結帳前試算結果。一律以 HTTP 200 回傳——即使優惠券不適用也不是錯誤，
 * 由 {@code couponApplied=false} 搭配 {@code couponMessage}（例如「未達優惠券最低消費金額」）
 * 讓前端在結帳頁就地顯示為提示而非錯誤。
 *
 * <p>金額用購物車「現價」計算，與實際結帳一致；不鎖庫存、不消耗優惠券。
 */
public record OrderPreviewResponse(
        BigDecimal subtotalAmount,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        boolean couponApplied,
        String couponCode,
        String couponName,
        String couponMessage) {}
