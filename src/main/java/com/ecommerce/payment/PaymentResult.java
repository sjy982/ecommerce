package com.ecommerce.payment;

/**
 * PG 결제(또는 결제 조회)의 결과를 워커가 판단하기 좋게 나눈 것.
 * PG가 직접 돌려주는 건 SUCCESS / DECLINED(/ NOT_FOUND)이고,
 * RETRYABLE / UNKNOWN은 응답을 받지 못했거나 처리되지 않았다는 신호를 우리 쪽에서 분류한 것이다.
 */
public enum PaymentResult {
    /** 승인됨 */
    SUCCESS,
    /** 확정 실패 (잔액 부족, 한도 초과, 카드 정지 등). 다시 시도해도 같은 결과라 재시도하지 않는다. */
    DECLINED,
    /** 처리되지 않은 게 확실한 일시 실패 (연결 거부, 처리 전 오류). 재시도한다. */
    RETRYABLE,
    /** 결과 불명 (응답 대기 타임아웃 등). 결제됐을 수도 있으므로 실패로 처리하면 안 되고, 조회로 확인한다. */
    UNKNOWN,
    /** 결제 조회 전용: PG에 이 멱등키로 처리된 결제가 없다. */
    NOT_FOUND
}
