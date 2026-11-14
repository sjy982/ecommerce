package com.ecommerce.stock;

public enum StockRestoreResult {
    /** 이번 호출로 재고를 늘림 */
    RESTORED,
    /** 이미 복구한 주문이라 그대로 둠 (재시도·중복 실행) */
    ALREADY_RESTORED,
    /** 판매 중이 아님(키 없음). DB에는 PAYMENT_FAILED로 남아 있어 다음 판매 오픈 계산에 반영된다. */
    NOT_ON_SALE
}
