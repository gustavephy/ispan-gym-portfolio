package tw.com.ispan.gym.service.cart;

import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.dto.cart.AddCartItemRequest;
import tw.com.ispan.gym.dto.cart.CartItemResponse;
import tw.com.ispan.gym.dto.cart.CartResponse;
import tw.com.ispan.gym.dto.cart.UpdateCartItemRequest;
import tw.com.ispan.gym.entity.cart.CartItem;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.product.ProductStatus;
import tw.com.ispan.gym.exception.CartItemNotFoundException;
import tw.com.ispan.gym.exception.ForbiddenCartAccessException;
import tw.com.ispan.gym.exception.InsufficientStockException;
import tw.com.ispan.gym.exception.InvalidQuantityException;
import tw.com.ispan.gym.exception.product.ProductNotFoundException;
import tw.com.ispan.gym.exception.product.ProductUnavailableException;
import tw.com.ispan.gym.repository.cart.CartItemRepository;
import tw.com.ispan.gym.repository.product.ProductRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CartService {
    private final CartItemRepository cartItems;
    private final ProductRepository products;
    private final MemberRepository users;

    public CartService(CartItemRepository cartItems, ProductRepository products, MemberRepository users) {
        this.cartItems = cartItems; this.products = products; this.users = users;
    }

    public CartResponse getCart(Integer userId) {
        List<CartItemResponse> items = cartItems.findAllByMemberIdOrderByCreatedAtAsc(userId)
                .stream().map(this::toResponse).toList();
        BigDecimal total = items.stream().map(CartItemResponse::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(items, total);
    }

    @Transactional
    public CartItemResponse addItem(Integer userId, AddCartItemRequest request) {
        validateQuantity(request.quantity());
        // Locking the owning user serializes concurrent additions for the same cart and
        // prevents two requests from racing against UX_cart_user_product.
        Member user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到會員資料"));
        Product product = products.findById(request.productId())
                .orElseThrow(() -> new ProductNotFoundException(request.productId()));
        // 購物車只裝商品；課程走直接結帳（createDirectOrder），
        // 課程獨立成表後，products 表本身就不會出現課程，無需再檢查型別。
        validateAvailable(product);

        CartItem item;
        var existingItem = cartItems.findByMemberIdAndProductProductId(userId, request.productId());
        if (existingItem.isPresent()) {
            item = existingItem.get();
            try {
                item.increaseBy(request.quantity());
            } catch (ArithmeticException ex) {
                throw new InvalidQuantityException();
            }
        } else {
            item = new CartItem(user, product, request.quantity());
        }
        validateStock(product, item.getQuantity());
        return toResponse(cartItems.save(item));
    }

    @Transactional
    public CartItemResponse updateQuantity(Integer userId, Long cartItemId, UpdateCartItemRequest request) {
        validateQuantity(request.quantity());
        CartItem item = ownedItem(userId, cartItemId);
        validateAvailable(item.getProduct());
        validateStock(item.getProduct(), request.quantity());
        item.changeQuantity(request.quantity());
        return toResponse(item);
    }

    @Transactional
    public void removeItem(Integer userId, Long cartItemId) {
        cartItems.delete(ownedItem(userId, cartItemId));
    }

    @Transactional
    public void clearCart(Integer userId) {
        cartItems.deleteAllByMemberId(userId);
    }

    private CartItem ownedItem(Integer userId, Long cartItemId) {
        CartItem item = cartItems.findById(cartItemId)
                .orElseThrow(() -> new CartItemNotFoundException(cartItemId));
        if (!item.getMember().getId().equals(userId)) {
            throw new ForbiddenCartAccessException(cartItemId);
        }
        return item;
    }

    private void validateQuantity(Long quantity) {
        if (quantity == null || quantity < 1) {
            throw new InvalidQuantityException();
        }
    }

    private void validateAvailable(Product product) {
        if (!ProductStatus.ACTIVE.equals(product.getStatus())) {
            throw new ProductUnavailableException(product.getProductId());
        }
    }

    private void validateStock(Product product, long requestedQuantity) {
        if (requestedQuantity > product.getStock()) {
            throw new InsufficientStockException(product.getProductId(), requestedQuantity, product.getStock());
        }
    }

    private CartItemResponse toResponse(CartItem item) {
        Product product = item.getProduct();
        BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new CartItemResponse(item.getId(), product.getProductId(), product.getName(),
                product.getImageUrl(), product.getPrice(), item.getQuantity(), subtotal);
    }
}

