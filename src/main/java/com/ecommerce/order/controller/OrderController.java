package com.ecommerce.order.controller;

import java.util.List;

import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ecommerce.common.response.ApiResponse;
import com.ecommerce.common.response.ApiResponseUtil;
import com.ecommerce.order.DTO.OrderProductDto;
import com.ecommerce.order.DTO.OrderProductRequestDto;
import com.ecommerce.order.DTO.OrderProductResponseDto;
import com.ecommerce.order.service.OrderService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("api/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    @PostMapping("/payment")
    public ResponseEntity<ApiResponse<OrderProductResponseDto>> orderProductAndPaymentRequest(@RequestBody @Valid OrderProductRequestDto dto) {
        String providerId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        OrderProductResponseDto responseDto = orderService.orderProductAndPaymentRequest(providerId, dto);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResponseUtil.createResponse(HttpStatus.OK.value(), responseDto, "order success"));
    }

    @GetMapping("/user")
    public ResponseEntity<ApiResponse<List<OrderProductResponseDto>>> getAllUserOrders() {
        String providerId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        List<OrderProductResponseDto> responseDtoList = orderService.getUserAllOrderProductsResponseDto(providerId);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResponseUtil.createResponse(HttpStatus.OK.value(), responseDtoList, "get all user order success"));
    }

    @GetMapping("/store")
    public ResponseEntity<ApiResponse<List<OrderProductResponseDto>>> getAllStoreOrders() {
        Long storeId = Long.parseLong((String) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        List<OrderProductResponseDto> responseDtoList = orderService.getStoreAllOrderProductsResponseDto(storeId);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResponseUtil.createResponse(HttpStatus.OK.value(), responseDtoList, "get all store order success"));
    }

    @GetMapping("/user/{orderId}")
    public ResponseEntity<ApiResponse<OrderProductResponseDto>> getUserOrder(@PathVariable Long orderId) {
        String providerId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        OrderProductResponseDto responseDto = orderService.getUserOrderProductResponseDto(orderId, providerId);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResponseUtil.createResponse(HttpStatus.OK.value(), responseDto, "get user order success"));
    }

    @GetMapping("/store/{orderId}")
    public ResponseEntity<ApiResponse<OrderProductResponseDto>> getStoreOrder(@PathVariable Long orderId) {
        Long storeId = Long.parseLong((String) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        OrderProductResponseDto responseDto = orderService.getStoreOrderProductResponseDto(orderId, storeId);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResponseUtil.createResponse(HttpStatus.OK.value(), responseDto, "get store order success"));
    }
}
