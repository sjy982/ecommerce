package com.ecommerce.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentGateway {
    /** PG 호출 지연을 흉내 내는 대기 시간. 기본 3초, 테스트에서는 0으로 줄인다. */
    private final long delayMs;

    public MockPaymentGateway(@Value("${payment.mock.delay-ms:3000}") long delayMs) {
        this.delayMs = delayMs;
    }

    public boolean pay() {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("모의 결제 중 인터럽트 발생", e);
        }
        return true;
    }
}
