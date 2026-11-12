package com.ecommerce.order.service;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.notification.service.NotificationService;
import com.ecommerce.order.DTO.OrderProductDto;
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
import com.ecommerce.store.model.Store;

import com.ecommerce.store.service.StoreService;
import com.ecommerce.user.model.Users;

import com.ecommerce.user.service.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final UserService userService;
    private final NotificationService notificationService;
    private final StoreService storeService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final PaymentJobRepository paymentJobRepository;
    @Transactional
    public OrderProductResponseDto orderProduct(String userId, OrderProductRequestDto dto) {
        Product product = productService.findById(dto.getProductId());
        productService.decreaseStock(product.getId(), dto.getQuantity());

        Store store = product.getStore();
        storeService.increaseTotalSales(store.getId(), product.getPrice() * dto.getQuantity());

        Users user = userService.findByProviderId(userId);
        Orders order = Orders.builder()
                             .user(user)
                             .store(store)
                             .product(product)
                             .quantity(dto.getQuantity())
                             .deliveryAddress(dto.getDeliveryAddress())
                             .phoneNumber(dto.getPhoneNumber())
                             .build();

        orderRepository.save(order);
        notificationService.createNotification(order);

        return convertOrdersToOrderProductResponse(order);
    }

    @Transactional
    public OrderProductResponseDto orderProductAndPaymentRequest(String providerId, OrderProductRequestDto dto) {
        final Product product = productService.findByIdUpdate(dto.getProductId());
        product.decreaseStock(dto.getQuantity());

        final Orders order = Orders.builder()
                                   .user(userService.findByProviderId(providerId))
                                   .store(product.getStore())
                                   .product(product)
                                   .quantity(dto.getQuantity())
                                   .deliveryAddress(dto.getDeliveryAddress())
                                   .phoneNumber(dto.getPhoneNumber())
                                   .status(OrderStatus.PENDING_PAYMENT)
                                   .build();
        orderRepository.save(order);

        // 빠른 경로가 처리할 job이므로 주문과 함께 선점된 상태(PROCESSING)로 만든다.
        // 커밋 후 요청 스레드에서 선점 UPDATE를 따로 하지 않아도 되어 커넥션을 추가로 빌리지 않는다.
        final PaymentJob paymentJob = PaymentJob.builder()
                                                .status(PaymentJobStatus.PROCESSING)
                                                .orderId(order.getId()).build();
        paymentJobRepository.save(paymentJob);

        applicationEventPublisher.publishEvent(PaymentJobCreatedEvent.builder()
                                                       .id(paymentJob.getId()).build());

        return convertOrdersToOrderProductResponse(order);
    }

    public List<OrderProductResponseDto> getUserAllOrderProductsResponseDto(String providerId) {
        List<Orders> orders = orderRepository.findAllByUserProviderIdOrderByOrderDateDesc(providerId);
        return orders.stream()
                .map((order) -> convertOrdersToOrderProductResponse(order)).toList();
    }

    public List<OrderProductResponseDto> getStoreAllOrderProductsResponseDto(Long storeId) {
        List<Orders> orders = orderRepository.findAllByStoreIdOrderByOrderDateDesc(storeId);
        return orders.stream()
                .map((order) -> convertOrdersToOrderProductResponse(order)).toList();
    }

    public OrderProductResponseDto getUserOrderProductResponseDto(Long orderId, String providerId) {
        Orders order = findByIdAndProviderId(orderId, providerId);
        return convertOrdersToOrderProductResponse(order);
    }

    public OrderProductResponseDto getStoreOrderProductResponseDto(Long orderId, Long storeId) {
        Orders order = findByIdAndStoreId(orderId, storeId);
        return convertOrdersToOrderProductResponse(order);
    }

    private static OrderProductResponseDto convertOrdersToOrderProductResponse(Orders order) {
        return OrderProductResponseDto.builder()
                                      .orderProduct(OrderProductDto.builder()
                                                            .name(order.getProduct().getName())
                                                            .price(order.getProduct().getPrice())
                                                            .quantity(order.getQuantity()).build())
                                      .deliveryAddress(order.getDeliveryAddress())
                                      .phoneNumber(order.getPhoneNumber())
                                      .status(order.getStatus()).build();
    }

    public Orders findById(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow(() -> new UsernameNotFoundException("order not found"));
    }

    private  Orders findByIdAndProviderId(Long orderId, String providerId) {
        Orders order = orderRepository.findByIdAndProviderId(orderId, providerId).orElseThrow(() -> new UsernameNotFoundException("order not found"));
        return order;
    }

    private  Orders findByIdAndStoreId(Long orderId, Long storeId) {
        Orders order = orderRepository.findByIdAndStoreId(orderId, storeId).orElseThrow(() -> new UsernameNotFoundException("order not found"));
        return order;
    }
}
