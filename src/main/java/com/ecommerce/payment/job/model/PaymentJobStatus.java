package com.ecommerce.payment.job.model;

public enum PaymentJobStatus {
    /** 결제 대기 (처음 또는 재시도 예약). 스위퍼가 nextAttemptAt이 지난 것을 가져간다. */
    PENDING,
    /** 워커가 선점해 결제 중 */
    PROCESSING,
    /** 결제는 최종 실패로 확정(주문 PAYMENT_FAILED)됐고, Redis 재고 복구만 남음. 스위퍼가 재시도한다. */
    RESTOCK_PENDING,
    /** 재시도를 다 했는데 결제 결과를 끝내 모름. 결제됐을 수 있어 자동으로 실패 처리하지 않고 사람이 확인한다. */
    NEEDS_REVIEW
}
