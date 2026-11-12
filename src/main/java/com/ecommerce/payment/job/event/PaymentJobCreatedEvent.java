package com.ecommerce.payment.job.event;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class PaymentJobCreatedEvent {
    private final long id;
}
