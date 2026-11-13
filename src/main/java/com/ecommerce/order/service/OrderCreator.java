package com.ecommerce.order.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.order.DTO.OrderProductRequestDto;
import com.ecommerce.order.DTO.OrderProductResponseDto;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.model.Orders;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.payment.job.event.PaymentJobCreatedEvent;
import com.ecommerce.payment.job.model.PaymentJob;
import com.ecommerce.payment.job.model.PaymentJobStatus;
import com.ecommerce.payment.job.repository.PaymentJobRepository;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.service.ProductService;
import com.ecommerce.user.service.UserService;

import lombok.RequiredArgsConstructor;

/**
 * 주문 접수의 DB 부분(트랜잭션). 재고 판정은 이미 Redis에서 끝났다.
 * product row를 잠그거나 UPDATE하지 않는다 → 주문들이 같은 row X-lock에 줄 서지 않고 병렬로 커밋된다.
 * (orders INSERT의 FK 확인으로 product row에 S-lock은 걸리지만, S-lock끼리는 서로 막지 않는다)
 */
@Service
@RequiredArgsConstructor
public class OrderCreator {
    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final UserService userService;
    private final PaymentJobRepository paymentJobRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public OrderProductResponseDto create(String providerId, OrderProductRequestDto dto, String requestId) {
        Product product = productService.findById(dto.getProductId());

        Orders order = Orders.builder()
                             .user(userService.findByProviderId(providerId))
                             .store(product.getStore())
                             .product(product)
                             .quantity(dto.getQuantity())
                             .deliveryAddress(dto.getDeliveryAddress())
                             .phoneNumber(dto.getPhoneNumber())
                             .status(OrderStatus.PENDING_PAYMENT)
                             .requestId(requestId)
                             .build();
        orderRepository.save(order);

        // 빠른 경로가 처리할 job이므로 주문과 함께 선점된 상태(PROCESSING)로 만든다.
        // 커밋 후 요청 스레드에서 선점 UPDATE를 따로 하지 않아도 되어 커넥션을 추가로 빌리지 않는다.
        PaymentJob paymentJob = PaymentJob.builder()
                                          .status(PaymentJobStatus.PROCESSING)
                                          .orderId(order.getId())
                                          .build();
        paymentJobRepository.save(paymentJob);

        applicationEventPublisher.publishEvent(PaymentJobCreatedEvent.builder().id(paymentJob.getId()).build());
        return OrderService.toResponse(order);
    }
}
