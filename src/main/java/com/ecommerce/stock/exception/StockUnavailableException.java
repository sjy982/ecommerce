package com.ecommerce.stock.exception;

/** Redis 장애 등으로 재고를 판정할 수 없음. 판정 없이 주문을 받으면 초과 판매가 될 수 있어 주문을 막는다. */
public class StockUnavailableException extends RuntimeException {
    public StockUnavailableException(Throwable cause) {
        super("재고를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.", cause);
    }
}
