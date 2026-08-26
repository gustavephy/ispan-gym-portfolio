package tw.com.ispan.gym.dto.cart;

import java.math.BigDecimal;

/**
 * 購物車單一品項。{@code unitPrice}／{@code subtotal} 一律用商品「現價」計算，
 * 結帳金額也以現價為準（見 OrderService）；{@code priceWhenAdded} 是加入購物車當下的價格快照
 * （{@code cart_item.unit_price_snapshot}），{@code priceChanged} 為真代表現價已與當初不同，
 * 前端據此顯示提示但不擋結帳。{@code available} 為假代表商品已下架或現有庫存不足這個數量。
 */
public record CartItemResponse(
        Integer cartItemId,
        Integer productId,
        String productName,
        String imageUrl,
        BigDecimal unitPrice,
        Integer quantity,
        BigDecimal subtotal,
        BigDecimal priceWhenAdded,
        boolean priceChanged,
        boolean available,
        Integer stock) {}
