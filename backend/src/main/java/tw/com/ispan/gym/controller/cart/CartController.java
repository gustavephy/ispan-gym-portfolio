package tw.com.ispan.gym.controller.cart;

import tw.com.ispan.gym.dto.cart.AddCartItemRequest;
import tw.com.ispan.gym.dto.cart.CartItemResponse;
import tw.com.ispan.gym.dto.cart.CartResponse;
import tw.com.ispan.gym.dto.cart.UpdateCartItemRequest;
import tw.com.ispan.gym.service.cart.CartService;
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
    public CartResponse getCart() { return cartService.getCart(currentUser.requireCurrentMemberId()); }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CartItemResponse addItem(@Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(currentUser.requireCurrentMemberId(), request);
    }

    @PutMapping("/items/{cartItemId}")
    public CartItemResponse updateItem(@PathVariable Integer cartItemId,
                                       @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.updateQuantity(currentUser.requireCurrentMemberId(), cartItemId, request);
    }

    @DeleteMapping("/items/{cartItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@PathVariable Integer cartItemId) {
        cartService.removeItem(currentUser.requireCurrentMemberId(), cartItemId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearCart() {
        cartService.clearCart(currentUser.requireCurrentMemberId());
    }
}

