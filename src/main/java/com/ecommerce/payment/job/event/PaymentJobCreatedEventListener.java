package com.ecommerce.payment.job.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ecommerce.payment.PaymentSubmitter;

import lombok.RequiredArgsConstructor;

/** 빠른 경로: 주문 트랜잭션이 커밋된 직후 결제 job을 바로 제출한다. */
@Component
@RequiredArgsConstructor
public class PaymentJobCreatedEventListener {
    private static final Logger log = LoggerFactory.getLogger(PaymentJobCreatedEventListener.class);

    private final PaymentSubmitter paymentSubmitter;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PaymentJobCreatedEvent event) {
        // job은 주문 트랜잭션 안에서 이미 선점(PROCESSING)됐으므로 DB 접근 없이 넣기만 한다.
        // AFTER_COMMIT에서 던진 예외는 커밋을 호출한 주문 요청까지 전파되므로 여기서 삼킨다(주문은 이미 커밋됨).
        try {
            paymentSubmitter.dispatchClaimed(event.getId());
        } catch (RuntimeException e) {
            log.error("결제 job {} 빠른 경로 제출 실패. 스위퍼가 다시 처리합니다.", event.getId(), e);
        }
    }
}
