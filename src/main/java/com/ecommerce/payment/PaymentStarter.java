package com.ecommerce.payment;

import org.springframework.stereotype.Component;

import com.ecommerce.order.model.Orders;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.payment.job.model.PaymentJob;
import com.ecommerce.payment.job.service.PaymentJobService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 워커가 하는 일: PG를 호출하고, 결과에 따라 완료 / 실패(+재고 복구) / 재시도 / 수동 확인을 정한다.
 *
 * 이중 결제 방지:
 * - 모든 시도에 같은 멱등키(주문 ID)를 보낸다 → PG가 같은 결제를 두 번 하지 않는다.
 * - 재시도(attempt > 0)에서는 먼저 조회한다 → 이미 승인됐으면 결제 없이 완료 처리만 한다.
 * - 결과를 모르면(UNKNOWN) 절대 실패로 처리하지 않는다 → 결제됐는데 재고를 되돌리는 일이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentStarter {
    private final PaymentGateway paymentGateway;
    private final PaymentService paymentService;
    private final PaymentJobService paymentJobService;
    private final OrderService orderService;
    private final PaymentRetryPolicy retryPolicy;

    public void start(Long paymentJobId) {
        PaymentJob job = paymentJobService.findById(paymentJobId);
        Orders order = orderService.findById(job.getOrderId());
        String key = PaymentService.paymentKey(order.getId());

        PaymentResult result = requestPayment(job, key, PaymentService.amountOf(order));
        switch (result) {
            case SUCCESS -> paymentService.completePayment(paymentJobId);
            case DECLINED -> fail(paymentJobId);
            default -> retryOrResolve(job, key, result);
        }
    }

    private PaymentResult requestPayment(PaymentJob job, String key, long amount) {
        try {
            if (job.getAttempt() > 0) {
                PaymentResult inquired = paymentGateway.inquire(key);
                if (inquired != PaymentResult.NOT_FOUND) {
                    return inquired;   // 이미 처리됨(SUCCESS/DECLINED) 또는 조회도 실패(UNKNOWN)
                }
            }
            return paymentGateway.pay(key, amount);
        } catch (RuntimeException e) {
            log.warn("결제 {} PG 호출 중 예외. 결과 불명으로 보고 재시도합니다.", key, e);
            return PaymentResult.UNKNOWN;
        }
    }

    private void retryOrResolve(PaymentJob job, String key, PaymentResult result) {
        int failedAttempts = job.getAttempt() + 1;
        if (!retryPolicy.isExhausted(failedAttempts)) {
            paymentJobService.scheduleRetry(job.getId(), failedAttempts, retryPolicy.nextAttemptAt(failedAttempts));
            log.info("결제 {} {} → {}번째 재시도 예약", key, result, failedAttempts);
            return;
        }
        // 재시도를 다 썼다: PG 조회로 최종 판단한다.
        switch (inquireSafely(key)) {
            case SUCCESS -> paymentService.completePayment(job.getId());
            case DECLINED, NOT_FOUND -> fail(job.getId());   // 결제되지 않은 게 확인됨
            default -> {
                paymentJobService.markNeedsReview(job.getId(), failedAttempts);
                log.error("결제 {} 결과를 끝내 확인하지 못했습니다. 수동 확인이 필요합니다(NEEDS_REVIEW).", key);
            }
        }
    }

    private PaymentResult inquireSafely(String key) {
        try {
            return paymentGateway.inquire(key);
        } catch (RuntimeException e) {
            return PaymentResult.UNKNOWN;
        }
    }

    private void fail(Long paymentJobId) {
        if (paymentService.failPayment(paymentJobId)) {
            paymentService.restock(paymentJobId);
        }
    }
}
