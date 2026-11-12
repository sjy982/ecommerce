package com.ecommerce.payment;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ecommerce.payment.job.service.PaymentJobService;

import lombok.RequiredArgsConstructor;

/**
 * 안전망. 빠른 경로에서 놓친(거절·예외 등) PENDING job을 주기적으로 다시 제출한다.
 * 결제 스레드 풀 큐의 남은 자리만큼만 꺼내므로 큐를 넘치게 넣지 않고, 나머지는 DB에서 다음 주기를 기다린다.
 * fixedDelay: 이전 실행이 끝난 뒤부터 주기를 세므로 실행이 겹치지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PaymentSweeper {
    private final PaymentJobService paymentJobService;
    private final PaymentSubmitter paymentSubmitter;

    @Scheduled(initialDelayString = "${payment.sweeper.initial-delay-ms:10000}",
               fixedDelayString = "${payment.sweeper.fixed-delay-ms:10000}")
    public void sweep() {
        int room = paymentSubmitter.remainingCapacity();
        if (room == 0) {
            return;
        }
        List<Long> pendingIds = paymentJobService.findPendingIds(room);
        for (Long paymentJobId : pendingIds) {
            paymentSubmitter.submit(paymentJobId);
        }
    }
}
