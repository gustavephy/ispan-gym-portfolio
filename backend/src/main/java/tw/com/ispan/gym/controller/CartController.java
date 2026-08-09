package tw.com.ispan.gym.controller;

import tw.com.ispan.gym.dto.cart.AddCartItemRequest;
import tw.com.ispan.gym.dto.cart.CartItemResponse;
import tw.com.ispan.gym.dto.cart.CartResponse;
import tw.com.ispan.gym.dto.cart.UpdateCartItemRequest;
import tw.com.ispan.gym.service.CartService;
import tw.com.ispan.gym.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
public class CartController {
    private final CartService cartService;
    private final CurrentUserProvider currentUser;

    public CartController(CartService cartService, CurrentUserProvider currentUser) {
        this.cartService = cartService; this.currentUser = currentUser;
    }

    @GetMapping
    public CartResponse getCart() { return cartService.getCart(currentUser.requireCurrentUserId()); }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CartItemResponse addItem(@Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(currentUser.requireCurrentUserId(), request);
    }

    @PutMapping("/items/{cartItemId}")
    public CartItemResponse updateItem(@PathVariable Long cartItemId,
                                       @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.updateQuantity(currentUser.requireCurrentUserId(), cartItemId, request);
    }

    @DeleteMapping("/items/{cartItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@PathVariable Long cartItemId) {
        cartService.removeItem(currentUser.requireCurrentUserId(), cartItemId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearCart() {
        cartService.clearCart(currentUser.requireCurrentUserId());
    }
}

