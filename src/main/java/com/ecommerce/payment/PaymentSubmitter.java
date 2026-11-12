package com.ecommerce.payment;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.ecommerce.payment.job.service.PaymentJobService;

/**
 * 결제 job을 결제 스레드 풀에 넣는 통로.
 *
 * 빠른 경로 {@link #dispatchClaimed}: job은 주문 트랜잭션 안에서 이미 PROCESSING(선점)으로 만들어졌으므로
 *   DB를 건드리지 않고 넣기만 한다. AFTER_COMMIT 시점의 요청 스레드는 아직 원래 커넥션을 쥐고 있어서,
 *   여기서 커넥션을 하나 더 빌리면 요청들이 서로 커넥션을 기다리는 커넥션 풀 기아가 생긴다.
 *
 * 스위퍼 경로 {@link #submit}: PENDING job을 조건부 UPDATE로 선점한 뒤 넣는다.
 *
 * 넣지 못했거나(거절) 워커에서 예외가 나면 선점을 풀어(PROCESSING → PENDING) 스위퍼가 다시 가져가게 한다.
 */
@Component
public class PaymentSubmitter {
    private static final Logger log = LoggerFactory.getLogger(PaymentSubmitter.class);

    private final PaymentJobService paymentJobService;
    private final PaymentStarter paymentStarter;
    private final ThreadPoolTaskExecutor paymentExecutor;
    private final TaskScheduler taskScheduler;

    public PaymentSubmitter(PaymentJobService paymentJobService,
                            PaymentStarter paymentStarter,
                            @Qualifier("paymentExecutor") ThreadPoolTaskExecutor paymentExecutor,
                            TaskScheduler taskScheduler) {
        this.paymentJobService = paymentJobService;
        this.paymentStarter = paymentStarter;
        this.paymentExecutor = paymentExecutor;
        this.taskScheduler = taskScheduler;
    }

    /** 빠른 경로. 이미 선점된 job을 DB 접근 없이 넣는다. @return 결제 스레드 풀에 들어갔으면 true */
    public boolean dispatchClaimed(Long paymentJobId) {
        if (execute(paymentJobId)) {
            return true;
        }
        // 거절된 경우에도 요청 스레드에서는 커넥션을 빌리지 않고, 스케줄러 스레드에서 PENDING으로 되돌린다.
        taskScheduler.schedule(() -> paymentJobService.release(paymentJobId), Instant.now());
        return false;
    }

    /** 스위퍼 경로. PENDING job을 선점한 뒤 넣는다. @return 이번 호출로 결제 스레드 풀에 들어갔으면 true */
    public boolean submit(Long paymentJobId) {
        if (!paymentJobService.claim(paymentJobId)) {
            return false;
        }
        if (execute(paymentJobId)) {
            return true;
        }
        paymentJobService.release(paymentJobId);
        return false;
    }

    /** 결제 스레드 풀 큐에 남은 자리 수. 스위퍼는 이만큼만 꺼낸다. */
    public int remainingCapacity() {
        return paymentExecutor.getThreadPoolExecutor().getQueue().remainingCapacity();
    }

    private boolean execute(Long paymentJobId) {
        try {
            paymentExecutor.execute(() -> run(paymentJobId));
            return true;
        } catch (TaskRejectedException e) {
            log.warn("결제 스레드 풀이 가득 차 job {}을(를) PENDING으로 되돌립니다. 스위퍼가 다시 처리합니다.", paymentJobId);
            return false;
        }
    }

    private void run(Long paymentJobId) {
        try {
            paymentStarter.start(paymentJobId);
        } catch (RuntimeException e) {
            log.error("결제 job {} 처리 중 예외. PENDING으로 되돌려 스위퍼가 재시도합니다.", paymentJobId, e);
            paymentJobService.release(paymentJobId);
        }
    }
}
