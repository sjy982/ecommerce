package com.ecommerce.payment.job.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.payment.job.model.PaymentJob;
import com.ecommerce.payment.job.model.PaymentJobStatus;
import com.ecommerce.payment.job.repository.PaymentJobRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentJobService {
    private final PaymentJobRepository paymentJobRepository;

    public PaymentJob findById(Long id) {
        return paymentJobRepository.findById(id).orElseThrow(() -> new UsernameNotFoundException("존재하지 않는다."));
    }

    /** 지금 실행할 PENDING job (재시도 예약 시각이 지난 것만) */
    public List<Long> findPendingIds(int limit) {
        return paymentJobRepository.findDueIds(PaymentJobStatus.PENDING, LocalDateTime.now(), PageRequest.of(0, limit));
    }

    public List<Long> findIdsByStatus(PaymentJobStatus status, int limit) {
        return paymentJobRepository.findIdsByStatus(status, PageRequest.of(0, limit));
    }

    /** 재시도 예약: PROCESSING → PENDING, 실패 횟수와 다음 시도 시각을 기록한다. */
    @Transactional
    public void scheduleRetry(Long id, int failedAttempts, LocalDateTime nextAttemptAt) {
        paymentJobRepository.transition(id, PaymentJobStatus.PROCESSING, PaymentJobStatus.PENDING,
                failedAttempts, nextAttemptAt);
    }

    /** 결과를 끝내 모름: 자동으로 실패 처리하지 않고 사람이 확인하도록 남긴다. */
    @Transactional
    public void markNeedsReview(Long id, int failedAttempts) {
        paymentJobRepository.transition(id, PaymentJobStatus.PROCESSING, PaymentJobStatus.NEEDS_REVIEW,
                failedAttempts, null);
    }

    @Transactional
    public void deleteIfStatus(Long id, PaymentJobStatus status) {
        paymentJobRepository.deleteByIdAndStatus(id, status);
    }

    /**
     * 선점: PENDING일 때만 PROCESSING으로 바꾼다. 빠른 경로와 스위퍼가 같은 job을 집어도 한 쪽만 성공한다.
     * 빠른 경로는 AFTER_COMMIT 리스너에서 호출하는데, 그 시점에는 이미 커밋된 원래 트랜잭션에
     * 참여하게 되어 변경이 커밋되지 않을 수 있으므로 항상 새 트랜잭션(REQUIRES_NEW)에서 커밋한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long id) {
        return paymentJobRepository.changeStatus(id, PaymentJobStatus.PENDING, PaymentJobStatus.PROCESSING) == 1;
    }

    /** 선점 해제: PROCESSING이면 PENDING으로 되돌려 스위퍼가 다시 가져가게 한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long id) {
        paymentJobRepository.changeStatus(id, PaymentJobStatus.PROCESSING, PaymentJobStatus.PENDING);
    }

    @Transactional
    public void deletePaymentJob(PaymentJob job) {
        paymentJobRepository.delete(job);
    }
}
