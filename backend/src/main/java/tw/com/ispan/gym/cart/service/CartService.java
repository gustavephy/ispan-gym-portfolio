package tw.com.ispan.gym.cart.service;

import tw.com.ispan.gym.cart.dto.*;
import tw.com.ispan.gym.cart.entity.CartItem;
import tw.com.ispan.gym.cart.exception.*;
import tw.com.ispan.gym.cart.repository.CartItemRepository;
import tw.com.ispan.gym.common.exception.ResourceNotFoundException;
import tw.com.ispan.gym.entity.Product;
import tw.com.ispan.gym.product.repository.ProductRepository;
import tw.com.ispan.gym.user.entity.User;
import tw.com.ispan.gym.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CartService {
    private final CartItemRepository cartItems;
    private final ProductRepository products;
    private final UserRepository users;

    public CartService(CartItemRepository cartItems, ProductRepository products, UserRepository users) {
        this.cartItems = cartItems; this.products = products; this.users = users;
    }

    public CartResponse getCart(Long userId) {
        List<CartItemResponse> items = cartItems.findAllByUserIdOrderByCreatedAtAsc(userId)
                .stream().map(this::toResponse).toList();
        BigDecimal total = items.stream().map(CartItemResponse::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(items, total);
    }

    @Transactional
    public CartItemResponse addItem(Long userId, AddCartItemRequest request) {
        validateQuantity(request.quantity());
        // Locking the owning user serializes concurrent additions for the same cart and
        // prevents two requests from racing against UX_cart_user_product.
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到指定的會員"));
        Product product = products.findById(request.productId())
                .orElseThrow(() -> new ProductNotFoundException(request.productId()));
        validateAvailable(product);

        CartItem item;
        var existingItem = cartItems.findByUserIdAndProductId(userId, request.productId());
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
    public CartItemResponse updateQuantity(Long userId, Long cartItemId, UpdateCartItemRequest request) {
        validateQuantity(request.quantity());
        CartItem item = ownedItem(userId, cartItemId);
        validateAvailable(item.getProduct());
        validateStock(item.getProduct(), request.quantity());
        item.changeQuantity(request.quantity());
        return toResponse(item);
    }

    @Transactional
    public void removeItem(Long userId, Long cartItemId) {
        cartItems.delete(ownedItem(userId, cartItemId));
    }

    @Transactional
    public void clearCart(Long userId) {
        cartItems.deleteAllByUserId(userId);
    }

    private CartItem ownedItem(Long userId, Long cartItemId) {
        CartItem item = cartItems.findById(cartItemId)
                .orElseThrow(() -> new CartItemNotFoundException(cartItemId));
        if (!item.getUser().getId().equals(userId)) {
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
        if (!"ACTIVE".equals(product.getStatus())) {
            throw new ProductUnavailableException(product.getId());
        }
    }

    private void validateStock(Product product, long requestedQuantity) {
        if ("GOODS".equals(product.getType()) && requestedQuantity > product.getStock()) {
            throw new InsufficientStockException(product.getId(), requestedQuantity, product.getStock());
        }
    }

    private CartItemResponse toResponse(CartItem item) {
        Product product = item.getProduct();
        BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new CartItemResponse(item.getId(), product.getId(), product.getType(), product.getName(),
                product.getImageUrl(), product.getPrice(), item.getQuantity(), subtotal);
    }
}

