package com.ecommerce.payment;

/**
 * PG 연동 계약.
 * paymentKey는 결제(주문)마다 고정된 멱등키다. 같은 키로 다시 요청하면 PG는 새로 결제하지 않고 처음 결과를 돌려준다.
 * (예: 토스페이먼츠 Idempotency-Key 헤더)
 */
public interface PaymentGateway {
    /** @return SUCCESS / DECLINED / RETRYABLE / UNKNOWN */
    PaymentResult pay(String paymentKey, long amount);

    /** @return SUCCESS / DECLINED / NOT_FOUND, 조회 자체가 실패하면 UNKNOWN */
    PaymentResult inquire(String paymentKey);
}
