package com.ecommerce.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SaleOpenResponseDto {
    private Long productId;
    /** Redis에 적재한 판매 가능 수량 */
    private long stock;
}
