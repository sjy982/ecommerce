package com.ecommerce.cart.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ecommerce.cart.DTO.AddItemToCartRequestDto;
import com.ecommerce.cart.DTO.AddItemToCartResponseDto;
import com.ecommerce.cart.DTO.CartItemResponseDto;
import com.ecommerce.cart.service.CartService;
import com.ecommerce.common.response.ApiResponse;
import com.ecommerce.common.response.ApiResponseUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("api/cart")
@RequiredArgsConstructor
public class CartController {
    private final CartService cartService;

    @PostMapping("/item")
    public ResponseEntity<ApiResponse<AddItemToCartResponseDto>> addItemToCart(@RequestBody @Valid AddItemToCartRequestDto dto) {
        String providerId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        AddItemToCartResponseDto responseDto = cartService.addItemToCart(providerId, dto);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResponseUtil.createResponse(HttpStatus.OK.value(), responseDto, "add item to cart success"));
    }

    @GetMapping("/items")
    public ResponseEntity<ApiResponse<List<CartItemResponseDto>>> getCartItems() {
        String providerId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        List<CartItemResponseDto> items = cartService.getCartItems(providerId);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResponseUtil.createResponse(HttpStatus.OK.value(), items, "select items success"));
    }

}
