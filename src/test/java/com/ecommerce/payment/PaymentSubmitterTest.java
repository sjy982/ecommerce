package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.ecommerce.payment.job.service.PaymentJobService;

/**
 * 빠른 경로: 선점에 성공한 job만 결제 스레드 풀에 넣고,
 * 넣지 못했거나(거절) 워커가 실패하면 선점을 풀어 스위퍼가 다시 가져가게 한다.
 */
class PaymentSubmitterTest {
    private PaymentJobService paymentJobService;
    private PaymentStarter paymentStarter;
    private ThreadPoolTaskExecutor executor;
    private ThreadPoolTaskScheduler scheduler;
    private final CountDownLatch blocker = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        paymentJobService = mock(PaymentJobService.class);
        paymentStarter = mock(PaymentStarter.class);
    }

    @AfterEach
    void tearDown() {
        blocker.countDown();
        if (executor != null) {
            executor.shutdown();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private PaymentSubmitter submitterWith(int threads, int queueCapacity) {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.initialize();
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("test-scheduler-");
        scheduler.initialize();
        return new PaymentSubmitter(paymentJobService, paymentStarter, executor, scheduler);
    }

    private void blockWorkers() {
        doAnswer(invocation -> {
            blocker.await(5, TimeUnit.SECONDS);
            return null;
        }).when(paymentStarter).start(anyLong());
    }

    @Test
    @DisplayName("선점에 성공하면 결제 스레드에서 결제를 실행한다")
    void givenClaimSucceeds_whenSubmit_thenPaymentRuns() {
        PaymentSubmitter submitter = submitterWith(2, 10);
        when(paymentJobService.claim(1L)).thenReturn(true);

        boolean submitted = submitter.submit(1L);

        assertThat(submitted).isTrue();
        verify(paymentStarter, timeout(2000)).start(1L);
    }

    @Test
    @DisplayName("선점에 실패하면(이미 누가 가져감) 결제를 실행하지 않는다")
    void givenClaimFails_whenSubmit_thenPaymentDoesNotRun() {
        PaymentSubmitter submitter = submitterWith(2, 10);
        when(paymentJobService.claim(1L)).thenReturn(false);

        boolean submitted = submitter.submit(1L);

        assertThat(submitted).isFalse();
        verify(paymentStarter, after(300).never()).start(anyLong());
    }

    @Test
    @DisplayName("스레드 풀이 가득 차 거절되면 선점을 풀어 PENDING으로 되돌린다")
    void givenExecutorFull_whenSubmit_thenReleaseClaim() {
        PaymentSubmitter submitter = submitterWith(1, 1);
        blockWorkers();
        when(paymentJobService.claim(anyLong())).thenReturn(true);

        assertThat(submitter.submit(1L)).isTrue();   // 실행 중
        assertThat(submitter.submit(2L)).isTrue();   // 큐 대기
        boolean third = submitter.submit(3L);        // 거절

        assertThat(third).isFalse();
        verify(paymentJobService).release(3L);
        verify(paymentJobService, never()).release(1L);
        verify(paymentJobService, never()).release(2L);
    }

    @Test
    @DisplayName("결제 도중 예외가 나면 선점을 풀어 다음 스위핑에서 다시 처리되게 한다")
    void givenWorkerThrows_whenSubmit_thenReleaseClaim() {
        PaymentSubmitter submitter = submitterWith(1, 10);
        when(paymentJobService.claim(1L)).thenReturn(true);
        doThrow(new RuntimeException("PG timeout")).when(paymentStarter).start(1L);

        submitter.submit(1L);

        verify(paymentJobService, timeout(2000)).release(1L);
    }

    @Test
    @DisplayName("빠른 경로: 주문과 함께 이미 선점된 job은 DB를 건드리지 않고 바로 결제 스레드에 넣는다")
    void givenClaimedJob_whenDispatchClaimed_thenRunsWithoutDbOnCallerThread() {
        PaymentSubmitter submitter = submitterWith(2, 10);

        boolean dispatched = submitter.dispatchClaimed(1L);

        assertThat(dispatched).isTrue();
        verify(paymentStarter, timeout(2000)).start(1L);
        // 요청 스레드(AFTER_COMMIT)에서 커넥션을 하나 더 빌리면 커넥션 풀 기아가 생기므로 선점 쿼리를 하지 않는다
        verify(paymentJobService, never()).claim(anyLong());
        verify(paymentJobService, never()).release(anyLong());
    }

    @Test
    @DisplayName("빠른 경로가 거절되면 PENDING 되돌리기는 요청 스레드가 아닌 다른 스레드에서 한다")
    void givenExecutorFull_whenDispatchClaimed_thenReleaseOnAnotherThread() {
        PaymentSubmitter submitter = submitterWith(1, 1);
        blockWorkers();
        AtomicReference<String> releaseThread = new AtomicReference<>();
        doAnswer(invocation -> {
            releaseThread.set(Thread.currentThread().getName());
            return null;
        }).when(paymentJobService).release(3L);

        assertThat(submitter.dispatchClaimed(1L)).isTrue();   // 실행 중
        assertThat(submitter.dispatchClaimed(2L)).isTrue();   // 큐 대기
        boolean third = submitter.dispatchClaimed(3L);        // 거절

        assertThat(third).isFalse();
        verify(paymentJobService, timeout(2000)).release(3L);
        assertThat(releaseThread.get()).isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("남은 큐 자리 수를 알려준다")
    void givenQueuedJobs_whenRemainingCapacity_thenQueueRoomLeft() {
        PaymentSubmitter submitter = submitterWith(1, 3);
        blockWorkers();
        when(paymentJobService.claim(anyLong())).thenReturn(true);

        submitter.submit(1L);   // 실행 중 (큐 소비 안 함)
        verify(paymentStarter, timeout(2000)).start(1L);
        submitter.submit(2L);   // 큐 1칸 사용

        assertThat(submitter.remainingCapacity()).isEqualTo(2);
    }
}
