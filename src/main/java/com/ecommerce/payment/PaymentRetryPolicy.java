package com.ecommerce.payment;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 결제 재시도 정책.
 * - 최대 시도 횟수: 기본 5회. 넘기면 PG 조회로 최종 판단한다 (결과를 끝내 모르면 수동 확인).
 * - 백오프: 실패할수록 간격을 늘린다 (기본 10초 → 30초 → 1분 → 5분). PG 장애 중에 요청을 쏟아붓지 않기 위해서다.
 *   실제 재시도 시각은 백오프 이후 첫 스위퍼 주기(10초)다.
 */
@Component
public class PaymentRetryPolicy {
    private final int maxAttempts;
    private final List<Long> backoffMs;

    public PaymentRetryPolicy(@Value("${payment.retry.max-attempts:5}") int maxAttempts,
                              @Value("${payment.retry.backoff-ms:10000,30000,60000,300000}") List<Long> backoffMs) {
        this.maxAttempts = maxAttempts;
        this.backoffMs = backoffMs;
    }

    /** @param failedAttempts 지금까지 실패한 시도 수 (이번 실패 포함) */
    public boolean isExhausted(int failedAttempts) {
        return failedAttempts >= maxAttempts;
    }

    public LocalDateTime nextAttemptAt(int failedAttempts) {
        int index = Math.min(failedAttempts, backoffMs.size()) - 1;
        return LocalDateTime.now().plusNanos(backoffMs.get(Math.max(index, 0)) * 1_000_000);
    }
}
