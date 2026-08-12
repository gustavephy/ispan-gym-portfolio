package tw.com.ispan.gym.service;

import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.dto.cart.AddCartItemRequest;
import tw.com.ispan.gym.dto.cart.CartItemResponse;
import tw.com.ispan.gym.dto.cart.CartResponse;
import tw.com.ispan.gym.dto.cart.UpdateCartItemRequest;
import tw.com.ispan.gym.entity.CartItem;
import tw.com.ispan.gym.entity.Product;
import tw.com.ispan.gym.entity.Member;
import tw.com.ispan.gym.enums.ProductStatus;
import tw.com.ispan.gym.exception.CartItemNotFoundException;
import tw.com.ispan.gym.exception.ForbiddenCartAccessException;
import tw.com.ispan.gym.exception.InsufficientStockException;
import tw.com.ispan.gym.exception.InvalidQuantityException;
import tw.com.ispan.gym.exception.ProductNotFoundException;
import tw.com.ispan.gym.exception.ProductUnavailableException;
import tw.com.ispan.gym.repository.CartItemRepository;
import tw.com.ispan.gym.repository.ProductRepository;
import tw.com.ispan.gym.repository.MemberRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CartService {
    private final CartItemRepository cartItems;
    private final ProductRepository products;
    private final MemberRepository members;

    public CartService(CartItemRepository cartItems, ProductRepository products, MemberRepository members) {
        this.cartItems = cartItems; this.products = products; this.members = members;
    }

    public CartResponse getCart(Integer memberId) {
        List<CartItemResponse> items = cartItems.findAllByMemberMemberIdOrderByCreatedAtAsc(memberId)
                .stream().map(this::toResponse).toList();
        BigDecimal total = items.stream().map(CartItemResponse::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(items, total);
    }

    @Transactional
    public CartItemResponse addItem(Integer memberId, AddCartItemRequest request) {
        validateQuantity(request.quantity());
        // Locking the owning member serializes concurrent additions for the same cart and
        // prevents two requests from racing against UX_cart_member_product.
        Member member = members.findByIdForUpdate(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到會員資料"));
        Product product = products.findById(request.productId())
                .orElseThrow(() -> new ProductNotFoundException(request.productId()));
        // 購物車只裝商品；課程走直接結帳（createDirectOrder），
        // 課程獨立成表後，products 表本身就不會出現課程，無需再檢查型別。
        validateAvailable(product);

        CartItem item;
        var existingItem = cartItems.findByMemberMemberIdAndProductProductId(memberId, request.productId());
        if (existingItem.isPresent()) {
            item = existingItem.get();
            try {
                item.increaseBy(request.quantity());
            } catch (ArithmeticException ex) {
                throw new InvalidQuantityException();
            }
        } else {
            item = new CartItem(member, product, request.quantity());
        }
        validateStock(product, item.getQuantity());
        return toResponse(cartItems.save(item));
    }

    @Transactional
    public CartItemResponse updateQuantity(Integer memberId, Long cartItemId, UpdateCartItemRequest request) {
        validateQuantity(request.quantity());
        CartItem item = ownedItem(memberId, cartItemId);
        validateAvailable(item.getProduct());
        validateStock(item.getProduct(), request.quantity());
        item.changeQuantity(request.quantity());
        return toResponse(item);
    }

    @Transactional
    public void removeItem(Integer memberId, Long cartItemId) {
        cartItems.delete(ownedItem(memberId, cartItemId));
    }

    @Transactional
    public void clearCart(Integer memberId) {
        cartItems.deleteAllByMemberMemberId(memberId);
    }

    private CartItem ownedItem(Integer memberId, Long cartItemId) {
        CartItem item = cartItems.findById(cartItemId)
                .orElseThrow(() -> new CartItemNotFoundException(cartItemId));
        if (!item.getMember().getMemberId().equals(memberId)) {
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

