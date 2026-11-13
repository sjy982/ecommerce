package com.ecommerce.payment.job.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ecommerce.payment.job.model.PaymentJob;
import com.ecommerce.payment.job.model.PaymentJobStatus;
import com.ecommerce.payment.job.repository.PaymentJobRepository;

/**
 * 결제 작업 선점(claim)은 빠른 경로와 스위퍼가 동시에 같은 job을 집을 수 있으므로
 * "PENDING일 때만 PROCESSING으로 바꾼다"는 조건부 UPDATE로 원자적으로 동작해야 한다.
 * 실제 커밋이 필요하므로 테스트 트랜잭션을 걸지 않고 직접 정리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentJobServiceTest {
    @Autowired
    private PaymentJobService paymentJobService;

    @Autowired
    private PaymentJobRepository paymentJobRepository;

    private final List<Long> createdJobIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        paymentJobRepository.deleteAllById(createdJobIds);
        createdJobIds.clear();
    }

    private Long createJob(PaymentJobStatus status) {
        PaymentJob job = paymentJobRepository.save(PaymentJob.builder()
                .orderId(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE))
                .status(status)
                .build());
        createdJobIds.add(job.getId());
        return job.getId();
    }

    private PaymentJobStatus statusOf(Long id) {
        return paymentJobRepository.findById(id).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("PENDING job을 선점하면 true를 반환하고 PROCESSING으로 바뀐다")
    void givenPendingJob_whenClaim_thenTrueAndProcessing() {
        Long id = createJob(PaymentJobStatus.PENDING);

        boolean claimed = paymentJobService.claim(id);

        assertThat(claimed).isTrue();
        assertThat(statusOf(id)).isEqualTo(PaymentJobStatus.PROCESSING);
    }

    @Test
    @DisplayName("이미 PROCESSING인 job은 다시 선점할 수 없다")
    void givenProcessingJob_whenClaim_thenFalse() {
        Long id = createJob(PaymentJobStatus.PENDING);
        paymentJobService.claim(id);

        boolean claimedAgain = paymentJobService.claim(id);

        assertThat(claimedAgain).isFalse();
        assertThat(statusOf(id)).isEqualTo(PaymentJobStatus.PROCESSING);
    }

    @Test
    @DisplayName("같은 job을 여러 스레드가 동시에 선점해도 정확히 하나만 성공한다")
    void givenPendingJob_whenClaimConcurrently_thenOnlyOneWins() throws Exception {
        Long id = createJob(PaymentJobStatus.PENDING);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Boolean> task = () -> {
                    start.await();
                    return paymentJobService.claim(id);
                };
                results.add(pool.submit(task));
            }
            start.countDown();

            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }

            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("선점을 해제하면 PROCESSING job이 다시 PENDING이 된다")
    void givenProcessingJob_whenRelease_thenPending() {
        Long id = createJob(PaymentJobStatus.PENDING);
        paymentJobService.claim(id);

        paymentJobService.release(id);

        assertThat(statusOf(id)).isEqualTo(PaymentJobStatus.PENDING);
        assertThat(paymentJobService.claim(id)).isTrue();
    }

    @Test
    @DisplayName("PENDING job만 limit 개수 이내로 조회된다")
    void givenMixedJobs_whenFindPendingIds_thenOnlyPendingWithinLimit() {
        Long pending1 = createJob(PaymentJobStatus.PENDING);
        Long pending2 = createJob(PaymentJobStatus.PENDING);
        Long pending3 = createJob(PaymentJobStatus.PENDING);
        Long processing = createJob(PaymentJobStatus.PROCESSING);

        List<Long> all = paymentJobService.findPendingIds(1000);
        List<Long> limited = paymentJobService.findPendingIds(2);

        assertThat(all).contains(pending1, pending2, pending3).doesNotContain(processing);
        assertThat(limited).hasSize(2);
    }
}
