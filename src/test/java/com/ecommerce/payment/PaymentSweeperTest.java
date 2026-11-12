package com.ecommerce.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ecommerce.payment.job.service.PaymentJobService;

/**
 * 스위퍼: 빠른 경로에서 놓친 PENDING job을 주기적으로 다시 제출한다.
 * 결제 스레드 풀 큐의 남은 자리만큼만 꺼내서 넘치지 않게 한다.
 */
class PaymentSweeperTest {
    private PaymentJobService paymentJobService;
    private PaymentSubmitter paymentSubmitter;
    private PaymentSweeper sweeper;

    @BeforeEach
    void setUp() {
        paymentJobService = mock(PaymentJobService.class);
        paymentSubmitter = mock(PaymentSubmitter.class);
        sweeper = new PaymentSweeper(paymentJobService, paymentSubmitter);
    }

    @Test
    @DisplayName("큐에 남은 자리가 없으면 DB를 조회하지 않는다")
    void givenNoRoom_whenSweep_thenSkip() {
        when(paymentSubmitter.remainingCapacity()).thenReturn(0);

        sweeper.sweep();

        verify(paymentJobService, never()).findPendingIds(anyInt());
        verify(paymentSubmitter, never()).submit(any());
    }

    @Test
    @DisplayName("남은 자리만큼 PENDING job을 꺼내 모두 제출한다")
    void givenRoom_whenSweep_thenSubmitPendingUpToRoom() {
        when(paymentSubmitter.remainingCapacity()).thenReturn(5);
        when(paymentJobService.findPendingIds(5)).thenReturn(List.of(11L, 12L, 13L));

        sweeper.sweep();

        verify(paymentJobService).findPendingIds(5);
        verify(paymentSubmitter).submit(11L);
        verify(paymentSubmitter).submit(12L);
        verify(paymentSubmitter).submit(13L);
    }
}
