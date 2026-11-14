package com.ecommerce.payment.job.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
public class PaymentJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, columnDefinition = "VARCHAR(30) CHECK (status IN ('PENDING', 'PROCESSING', 'RESTOCK_PENDING', 'NEEDS_REVIEW'))")
    private PaymentJobStatus status;

    /** 지금까지 실패한 결제 시도 수. 0이면 첫 시도라 PG 조회 없이 바로 결제한다. */
    @Column(nullable = false)
    @Builder.Default
    private int attempt = 0;

    /** 재시도 예약 시각. 스위퍼는 이 시각이 지난 PENDING만 가져간다 (null이면 바로). */
    private LocalDateTime nextAttemptAt;

    public void markProcessing() {
        status = PaymentJobStatus.PROCESSING;
    }

    public void markRestockPending() {
        status = PaymentJobStatus.RESTOCK_PENDING;
    }
}
