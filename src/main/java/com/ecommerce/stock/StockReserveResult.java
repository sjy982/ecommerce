package com.ecommerce.stock;

/** Redis 재고 차감 결과 */
public enum StockReserveResult {
    /** 차감 성공 */
    RESERVED,
    /** 남은 수량 부족 (품절) */
    SOLD_OUT,
    /** Redis에 재고 키가 없음 = 판매 오픈 전이거나 판매 중이 아닌 상품 */
    NOT_ON_SALE
}
