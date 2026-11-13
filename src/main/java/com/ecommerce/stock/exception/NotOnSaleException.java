package com.ecommerce.stock.exception;

/**
 * 정상적인 거절 응답이다. 몰림 상황에서 수천 번 발생하므로 스택 트레이스를 만들지 않아 비용을 줄인다.
 */
public class NotOnSaleException extends RuntimeException {
    public NotOnSaleException() {
        super("판매 중인 상품이 아닙니다.", null, false, false);
    }
}
