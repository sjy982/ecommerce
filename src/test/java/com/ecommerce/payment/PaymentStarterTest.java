package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ecommerce.order.model.Orders;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.payment.job.model.PaymentJob;
import com.ecommerce.payment.job.model.PaymentJobStatus;
import com.ecommerce.payment.job.service.PaymentJobService;
import com.ecommerce.product.model.Product;

/**
 * 결제 워커의 판단: PG 결과를 보고 완료 / 실패(+재고 복구) / 재시도 / 수동 확인 중 무엇을 할지 정한다.
 */
class PaymentStarterTest {
    private static final Long JOB_ID = 1L;
    private static final Long ORDER_ID = 42L;
    private static final String KEY = "order-42";

    private PaymentGateway gateway;
    private PaymentService paymentService;
    private PaymentJobService paymentJobService;
    private OrderService orderService;
    private PaymentStarter starter;

    @BeforeEach
    void setUp() {
        gateway = mock(PaymentGateway.class);
        paymentService = mock(PaymentService.class);
        paymentJobService = mock(PaymentJobService.class);
        orderService = mock(OrderService.class);
        PaymentRetryPolicy retryPolicy = new PaymentRetryPolicy(3, List.of(10_000L, 30_000L));
        starter = new PaymentStarter(gateway, paymentService, paymentJobService, orderService, retryPolicy);

        Orders order = Orders.builder().id(ORDER_ID).quantity(2)
                             .product(Product.builder().id(7L).price(100L).build()).build();
        when(orderService.findById(ORDER_ID)).thenReturn(order);
    }

    private void jobWithFailedAttempts(int attempt) {
        when(paymentJobService.findById(JOB_ID)).thenReturn(PaymentJob.builder()
                .id(JOB_ID).orderId(ORDER_ID).status(PaymentJobStatus.PROCESSING).attempt(attempt).build());
    }

    @Test
    @DisplayName("승인되면 결제 완료 처리를 한다 (멱등키 = 주문 ID, 금액 = 가격 × 수량)")
    void givenApproved_whenStart_thenComplete() {
        jobWithFailedAttempts(0);
        when(gateway.pay(KEY, 200L)).thenReturn(PaymentResult.SUCCESS);

        starter.start(JOB_ID);

        verify(paymentService).completePayment(JOB_ID);
        verify(gateway, never()).inquire(anyString());   // 첫 시도는 조회 없이 바로 결제
    }

    @Test
    @DisplayName("확정 실패면 실패 처리 후 재고를 복구한다 (재시도하지 않음)")
    void givenDeclined_whenStart_thenFailAndRestock() {
        jobWithFailedAttempts(0);
        when(gateway.pay(KEY, 200L)).thenReturn(PaymentResult.DECLINED);
        when(paymentService.failPayment(JOB_ID)).thenReturn(true);

        starter.start(JOB_ID);

        verify(paymentService).failPayment(JOB_ID);
        verify(paymentService).restock(JOB_ID);
        verify(paymentJobService, never()).scheduleRetry(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("일시 실패면 시도 횟수를 올리고 백오프 후로 재시도를 예약한다")
    void givenRetryable_whenStart_thenScheduleRetry() {
        jobWithFailedAttempts(0);
        when(gateway.pay(KEY, 200L)).thenReturn(PaymentResult.RETRYABLE);
        LocalDateTime before = LocalDateTime.now();

        starter.start(JOB_ID);

        ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(paymentJobService).scheduleRetry(eq(JOB_ID), eq(1), at.capture());
        assertThat(at.getValue()).isAfterOrEqualTo(before.plusSeconds(10));
        verify(paymentService, never()).completePayment(anyLong());
        verify(paymentService, never()).failPayment(anyLong());
    }

    @Test
    @DisplayName("PG 호출에서 예외가 나면 결과 불명으로 보고 재시도를 예약한다 (실패 처리하지 않음)")
    void givenGatewayThrows_whenStart_thenScheduleRetry() {
        jobWithFailedAttempts(0);
        when(gateway.pay(KEY, 200L)).thenThrow(new RuntimeException("read timeout"));

        starter.start(JOB_ID);

        verify(paymentJobService).scheduleRetry(eq(JOB_ID), eq(1), any());
        verify(paymentService, never()).failPayment(anyLong());
    }

    @Test
    @DisplayName("재시도에서는 먼저 조회하고, 이미 승인됐으면 다시 결제하지 않는다")
    void givenRetryAndAlreadyApproved_whenStart_thenCompleteWithoutPay() {
        jobWithFailedAttempts(1);
        when(gateway.inquire(KEY)).thenReturn(PaymentResult.SUCCESS);

        starter.start(JOB_ID);

        verify(gateway, never()).pay(anyString(), anyLong());
        verify(paymentService).completePayment(JOB_ID);
    }

    @Test
    @DisplayName("재시도에서 조회 결과가 없으면(처리된 적 없음) 그때 결제한다")
    void givenRetryAndNotFound_whenStart_thenPay() {
        jobWithFailedAttempts(1);
        when(gateway.inquire(KEY)).thenReturn(PaymentResult.NOT_FOUND);
        when(gateway.pay(KEY, 200L)).thenReturn(PaymentResult.SUCCESS);

        starter.start(JOB_ID);

        verify(gateway).pay(KEY, 200L);
        verify(paymentService).completePayment(JOB_ID);
    }

    @Test
    @DisplayName("최대 횟수를 넘겼고 PG에 기록이 없으면(결제 안 됨 확인) 실패 처리하고 재고를 복구한다")
    void givenExhaustedAndNotFound_whenStart_thenFail() {
        jobWithFailedAttempts(2);
        when(gateway.inquire(KEY)).thenReturn(PaymentResult.NOT_FOUND);
        when(gateway.pay(KEY, 200L)).thenReturn(PaymentResult.RETRYABLE);
        when(paymentService.failPayment(JOB_ID)).thenReturn(true);

        starter.start(JOB_ID);

        verify(paymentService).failPayment(JOB_ID);
        verify(paymentService).restock(JOB_ID);
        verify(paymentJobService, never()).scheduleRetry(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("최대 횟수를 넘겼는데 결과를 끝내 모르면 실패 처리하지 않고 수동 확인으로 넘긴다")
    void givenExhaustedAndStillUnknown_whenStart_thenNeedsReview() {
        jobWithFailedAttempts(2);
        when(gateway.inquire(KEY)).thenReturn(PaymentResult.UNKNOWN);

        starter.start(JOB_ID);

        verify(paymentJobService).markNeedsReview(JOB_ID, 3);
        verify(paymentService, never()).failPayment(anyLong());
        verify(paymentService, never()).restock(anyLong());
    }
}
