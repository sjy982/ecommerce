package com.ecommerce.payment.job.event;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ecommerce.payment.PaymentSubmitter;

@ExtendWith(MockitoExtension.class)
class PaymentJobCreatedEventListenerTest {
    @Mock
    private PaymentSubmitter paymentSubmitter;

    @InjectMocks
    private PaymentJobCreatedEventListener listener;

    @Test
    @DisplayName("주문 커밋 후 이벤트를 받으면, 주문과 함께 선점된 job을 선점 쿼리 없이 바로 넣는다 (빠른 경로)")
    void givenEvent_whenHandle_thenDispatchClaimedJob() {
        listener.handle(PaymentJobCreatedEvent.builder().id(42L).build());

        verify(paymentSubmitter).dispatchClaimed(42L);
        verify(paymentSubmitter, never()).submit(anyLong());
    }

    @Test
    @DisplayName("제출 중 예외가 나도 주문 요청으로 전파하지 않는다 (주문은 이미 커밋됨)")
    void givenDispatchThrows_whenHandle_thenDoesNotPropagate() {
        doThrow(new RuntimeException("unexpected")).when(paymentSubmitter).dispatchClaimed(42L);

        assertThatCode(() -> listener.handle(PaymentJobCreatedEvent.builder().id(42L).build()))
                .doesNotThrowAnyException();
    }
}
