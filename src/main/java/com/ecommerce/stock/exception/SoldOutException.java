package com.ecommerce.stock.exception;

/**
 * 정상적인 거절 응답이다. 몰림 상황에서 수천 번 발생하므로 스택 트레이스를 만들지 않아 비용을 줄인다.
 */
public class SoldOutException extends RuntimeException {
    public SoldOutException() {
        super("품절되었습니다.", null, false, false);
    }
}
