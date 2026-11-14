package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ecommerce.payment.MockPaymentGateway.Outcome;

/**
 * 모의 PG도 실제 PG처럼 멱등키를 지킨다: 같은 키로 다시 요청하면 새로 결제하지 않고 처음 결과를 돌려준다.
 * 그래야 "재시도해도 이중 결제가 없다"를 우리 쪽 테스트로 검증할 수 있다.
 */
class MockPaymentGatewayTest {
    private static final String KEY = "order-1";

    private MockPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new MockPaymentGateway(0);
    }

    @Test
    @DisplayName("기본은 승인이고, 같은 멱등키로 다시 요청해도 실제 결제는 한 번뿐이다")
    void givenSameKey_whenPayTwice_thenChargedOnce() {
        PaymentResult first = gateway.pay(KEY, 100);
        PaymentResult second = gateway.pay(KEY, 100);

        assertThat(first).isEqualTo(PaymentResult.SUCCESS);
        assertThat(second).isEqualTo(PaymentResult.SUCCESS);
        assertThat(gateway.chargeCount(KEY)).isEqualTo(1);
    }

    @Test
    @DisplayName("확정 실패(잔액 부족 등)는 DECLINED이고 조회해도 DECLINED다")
    void givenDecline_whenPay_thenDeclined() {
        gateway.script(KEY, Outcome.DECLINE);

        assertThat(gateway.pay(KEY, 100)).isEqualTo(PaymentResult.DECLINED);
        assertThat(gateway.inquire(KEY)).isEqualTo(PaymentResult.DECLINED);
        assertThat(gateway.chargeCount(KEY)).isZero();
    }

    @Test
    @DisplayName("처리 전에 실패(연결 거부 등)하면 RETRYABLE이고 PG에는 기록이 없다")
    void givenFailBeforeProcessing_whenPay_thenRetryableAndNotFound() {
        gateway.script(KEY, Outcome.FAIL_BEFORE_PROCESSING);

        assertThat(gateway.pay(KEY, 100)).isEqualTo(PaymentResult.RETRYABLE);
        assertThat(gateway.inquire(KEY)).isEqualTo(PaymentResult.NOT_FOUND);
        assertThat(gateway.chargeCount(KEY)).isZero();
    }

    @Test
    @DisplayName("승인은 됐는데 응답이 끊기면 UNKNOWN이지만, 조회하면 SUCCESS다")
    void givenApprovedButResponseLost_whenPay_thenUnknownButInquirySuccess() {
        gateway.script(KEY, Outcome.APPROVE_BUT_LOSE_RESPONSE);

        assertThat(gateway.pay(KEY, 100)).isEqualTo(PaymentResult.UNKNOWN);
        assertThat(gateway.inquire(KEY)).isEqualTo(PaymentResult.SUCCESS);
        assertThat(gateway.pay(KEY, 100)).isEqualTo(PaymentResult.SUCCESS);   // 재요청해도 새로 결제하지 않음
        assertThat(gateway.chargeCount(KEY)).isEqualTo(1);
    }

    @Test
    @DisplayName("조회 API가 장애면 UNKNOWN을 돌려준다")
    void givenInquiryDown_whenInquire_thenUnknown() {
        gateway.failInquiry(KEY);

        assertThat(gateway.inquire(KEY)).isEqualTo(PaymentResult.UNKNOWN);
    }
}
