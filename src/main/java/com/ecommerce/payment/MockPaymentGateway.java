package com.ecommerce.payment;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 모의 PG. 기본은 항상 승인이고(부하 측정 조건 유지), 테스트에서 멱등키별로 결과를 지정할 수 있다.
 *
 * 실제 PG처럼 멱등키를 지킨다:
 * - 실제로 처리한 결제만 processed에 남는다 (승인 또는 확정 실패).
 * - 같은 키로 다시 pay를 부르면 새로 결제하지 않고 남아 있는 결과를 돌려준다.
 * 그래서 "승인은 됐는데 응답이 끊긴" 상황을 재현하고, 재시도해도 결제가 1번인지 셀 수 있다.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {
    /** 테스트에서 지정하는 한 번의 결제 시도 결과 */
    public enum Outcome {
        /** 승인 */
        APPROVE,
        /** 확정 실패 (잔액 부족 등) */
        DECLINE,
        /** 처리 전에 실패 (연결 거부, 5xx) → PG에 기록 없음 */
        FAIL_BEFORE_PROCESSING,
        /** 승인은 됐는데 응답이 끊김 (read timeout) → PG에는 승인으로 남음 */
        APPROVE_BUT_LOSE_RESPONSE,
        /** 응답이 끊겼고 실제로도 처리 안 됨 → 우리 쪽에서는 결과 불명 */
        LOSE_RESPONSE_UNPROCESSED
    }

    /** PG 호출 지연을 흉내 내는 대기 시간. 기본 3초, 테스트에서는 0으로 줄인다. */
    private final long delayMs;

    private final Map<String, PaymentResult> processed = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> charges = new ConcurrentHashMap<>();
    private final Map<String, Deque<Outcome>> scripts = new ConcurrentHashMap<>();
    private final Set<String> inquiryDown = ConcurrentHashMap.newKeySet();
    private volatile List<Outcome> nextNewKeyScript;
    private volatile boolean nextNewKeyInquiryDown;

    public MockPaymentGateway(@Value("${payment.mock.delay-ms:3000}") long delayMs) {
        this.delayMs = delayMs;
    }

    @Override
    public PaymentResult pay(String paymentKey, long amount) {
        sleep();
        synchronized (lockFor(paymentKey)) {
            PaymentResult already = processed.get(paymentKey);
            if (already != null) {
                return already;   // 멱등: 이미 처리한 결제는 다시 하지 않는다
            }
            switch (nextOutcome(paymentKey)) {
                case DECLINE:
                    processed.put(paymentKey, PaymentResult.DECLINED);
                    return PaymentResult.DECLINED;
                case FAIL_BEFORE_PROCESSING:
                    return PaymentResult.RETRYABLE;
                case APPROVE_BUT_LOSE_RESPONSE:
                    approve(paymentKey);
                    return PaymentResult.UNKNOWN;
                case LOSE_RESPONSE_UNPROCESSED:
                    return PaymentResult.UNKNOWN;
                case APPROVE:
                default:
                    approve(paymentKey);
                    return PaymentResult.SUCCESS;
            }
        }
    }

    @Override
    public PaymentResult inquire(String paymentKey) {
        if (inquiryDown.contains(paymentKey)) {
            return PaymentResult.UNKNOWN;
        }
        return processed.getOrDefault(paymentKey, PaymentResult.NOT_FOUND);
    }

    // ---- 테스트 지원 ----

    /** 이 키의 결제 시도 결과를 순서대로 지정한다. 다 쓰면 승인. */
    public void script(String paymentKey, Outcome... outcomes) {
        scripts.put(paymentKey, new ArrayDeque<>(List.of(outcomes)));
    }

    /** 다음에 처음 보는 키(= 다음 주문)에 적용할 결과. 주문 ID는 커밋 전에 알 수 없어서 쓴다. */
    public void scriptNextNewKey(Outcome... outcomes) {
        nextNewKeyScript = List.of(outcomes);
    }

    public void failInquiry(String paymentKey) {
        inquiryDown.add(paymentKey);
    }

    public void failInquiryForNextNewKey() {
        nextNewKeyInquiryDown = true;
    }

    /** 이 키로 실제 승인된 횟수 (멱등이면 최대 1) */
    public int chargeCount(String paymentKey) {
        AtomicInteger count = charges.get(paymentKey);
        return count == null ? 0 : count.get();
    }

    private void approve(String paymentKey) {
        processed.put(paymentKey, PaymentResult.SUCCESS);
        charges.computeIfAbsent(paymentKey, k -> new AtomicInteger()).incrementAndGet();
    }

    private Outcome nextOutcome(String paymentKey) {
        Deque<Outcome> script = scripts.get(paymentKey);
        if (script == null && nextNewKeyScript != null) {
            synchronized (this) {
                if (nextNewKeyScript != null) {
                    script = new ArrayDeque<>(nextNewKeyScript);
                    scripts.put(paymentKey, script);
                    nextNewKeyScript = null;
                    if (nextNewKeyInquiryDown) {
                        inquiryDown.add(paymentKey);
                        nextNewKeyInquiryDown = false;
                    }
                }
            }
        }
        if (script == null || script.isEmpty()) {
            return Outcome.APPROVE;
        }
        return script.poll();
    }

    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    private Object lockFor(String paymentKey) {
        return locks.computeIfAbsent(paymentKey, k -> new Object());
    }

    private void sleep() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("모의 결제 중 인터럽트 발생", e);
        }
    }
}
