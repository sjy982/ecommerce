package com.ecommerce.cart.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.cart.DTO.AddItemToCartRequestDto;
import com.ecommerce.cart.DTO.AddItemToCartResponseDto;
import com.ecommerce.cart.DTO.CartItemResponseDto;
import com.ecommerce.cart.model.Cart;
import com.ecommerce.cartItem.model.CartItem;
import com.ecommerce.cartItem.projection.CartItemProjection;

import com.ecommerce.cartItem.service.CartItemService;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.service.ProductService;
import com.ecommerce.user.service.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartService {
    private final ProductService productService;
    private final UserService userService;
    private final CartItemService cartItemService;

    @Transactional
    public AddItemToCartResponseDto addItemToCart(String providerId, AddItemToCartRequestDto dto) {
        Product product = productService.checkQuantity(dto.getProductId(), dto.getQuantity());

        Cart userCart = userService.findCartByProviderid(providerId);

        CartItem cartItem = CartItem.builder()
                .cart(userCart)
                .product(product)
                .quantity(dto.getQuantity()).build();

        cartItemService.save(cartItem);

        AddItemToCartResponseDto responseDto = AddItemToCartResponseDto.builder()
                .productName(cartItem.getProduct().getName())
                .quantity(cartItem.getQuantity()).build();
        return responseDto;
    }

    public List<CartItemResponseDto> getCartItems(String providerId) {
        Cart userCart = userService.findCartByProviderid(providerId);
        List<CartItemProjection> cartItems = cartItemService.findCartItemsByCartId(userCart.getId());
        return cartItems.stream()
                .map(item -> CartItemResponseDto.builder()
                        .cartItemId(item.getCartItemId())
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .productPrice(item.getProductPrice())
                        .productQuantity(item.getQuantity())
                        .build())
                .toList();
    }

}
