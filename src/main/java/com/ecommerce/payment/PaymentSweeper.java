package com.ecommerce.payment;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ecommerce.payment.job.model.PaymentJobStatus;
import com.ecommerce.payment.job.service.PaymentJobService;

import lombok.RequiredArgsConstructor;

/**
 * 안전망. 빠른 경로에서 놓친(거절·예외 등) PENDING job과 재시도 예약 시각이 지난 job을 주기적으로 다시 제출하고,
 * Redis 재고 복구가 남은 job(RESTOCK_PENDING)도 다시 복구한다.
 * 결제 스레드 풀 큐의 남은 자리만큼만 꺼내므로 큐를 넘치게 넣지 않고, 나머지는 DB에서 다음 주기를 기다린다.
 * fixedDelay: 이전 실행이 끝난 뒤부터 주기를 세므로 실행이 겹치지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PaymentSweeper {
    private static final Logger log = LoggerFactory.getLogger(PaymentSweeper.class);
    private static final int RESTOCK_BATCH = 100;

    private final PaymentJobService paymentJobService;
    private final PaymentSubmitter paymentSubmitter;
    private final PaymentService paymentService;

    @Scheduled(initialDelayString = "${payment.sweeper.initial-delay-ms:10000}",
               fixedDelayString = "${payment.sweeper.fixed-delay-ms:10000}")
    public void sweep() {
        retryRestock();
        int room = paymentSubmitter.remainingCapacity();
        if (room == 0) {
            return;
        }
        List<Long> pendingIds = paymentJobService.findPendingIds(room);
        for (Long paymentJobId : pendingIds) {
            paymentSubmitter.submit(paymentJobId);
        }
    }

    /** 결제는 실패로 확정됐지만 Redis 재고 복구가 남은 job. 복구는 멱등이라 몇 번 다시 해도 안전하다. */
    private void retryRestock() {
        for (Long paymentJobId : paymentJobService.findIdsByStatus(PaymentJobStatus.RESTOCK_PENDING, RESTOCK_BATCH)) {
            try {
                paymentService.restock(paymentJobId);
            } catch (RuntimeException e) {
                log.warn("job {} 재고 복구 재시도 실패. 다음 주기에 다시 시도합니다.", paymentJobId, e);
            }
        }
    }
}
