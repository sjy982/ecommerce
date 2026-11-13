package com.ecommerce.order.service;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.ecommerce.order.DTO.OrderProductDto;
import com.ecommerce.order.DTO.OrderProductRequestDto;
import com.ecommerce.order.DTO.OrderProductResponseDto;
import com.ecommerce.order.model.Orders;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.stock.RedisStockRepository;
import com.ecommerce.stock.StockReserveResult;
import com.ecommerce.stock.exception.NotOnSaleException;
import com.ecommerce.stock.exception.SoldOutException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepository;
    private final RedisStockRepository redisStockRepository;
    private final OrderCreator orderCreator;

    /**
     * 주문 접수. 트랜잭션을 걸지 않는다(Redis 차감과 보상이 DB 트랜잭션 바깥에서 일어나야 하므로).
     * 1) Redis에서 재고 확인 + 차감 → 품절·판매 중 아님이면 DB에 가지 않고 바로 거절
     * 2) DB에 주문·결제 job 저장 (OrderCreator의 트랜잭션, product row UPDATE 없음)
     * 3) 2)가 실패하면 저장되지 않은 게 확실할 때만 Redis를 되돌린다
     *    (줄일 때 Redis 먼저, 늘릴 때 DB 기준 → 어긋나도 "덜 팔림" 쪽으로만)
     */
    public OrderProductResponseDto orderProductAndPaymentRequest(String providerId, OrderProductRequestDto dto) {
        Long productId = dto.getProductId();
        int quantity = dto.getQuantity();

        StockReserveResult result = redisStockRepository.reserve(productId, quantity);
        if (result == StockReserveResult.SOLD_OUT) {
            throw new SoldOutException();
        }
        if (result == StockReserveResult.NOT_ON_SALE) {
            throw new NotOnSaleException();
        }

        String requestId = UUID.randomUUID().toString();
        try {
            return orderCreator.create(providerId, dto, requestId);
        } catch (RuntimeException e) {
            compensate(requestId, productId, quantity);
            throw e;
        }
    }

    /**
     * DB 저장 중 예외가 났을 때 Redis 재고를 되돌릴지 결정한다.
     * 커밋은 됐는데 응답만 끊긴 경우(결과 불명)에 되돌리면 초과 판매가 되므로, 주문이 없는 게 확인될 때만 되돌린다.
     * 확인조차 못 하면 되돌리지 않는다 → 덜 팔림으로 남고 판매 종료 후 DB 기준으로 맞춘다.
     */
    private void compensate(String requestId, Long productId, int quantity) {
        try {
            if (orderRepository.existsByRequestId(requestId)) {
                log.warn("주문 저장 중 예외가 났지만 주문은 커밋돼 있어 재고를 되돌리지 않습니다. requestId={}", requestId);
                return;
            }
            redisStockRepository.release(productId, quantity);
        } catch (RuntimeException e) {
            log.error("재고 보상 여부를 확인하지 못해 되돌리지 않습니다(덜 팔림). requestId={}, productId={}, quantity={}",
                    requestId, productId, quantity, e);
        }
    }

    public List<OrderProductResponseDto> getUserAllOrderProductsResponseDto(String providerId) {
        List<Orders> orders = orderRepository.findAllByUserProviderIdOrderByOrderDateDesc(providerId);
        return orders.stream().map(OrderService::toResponse).toList();
    }

    public List<OrderProductResponseDto> getStoreAllOrderProductsResponseDto(Long storeId) {
        List<Orders> orders = orderRepository.findAllByStoreIdOrderByOrderDateDesc(storeId);
        return orders.stream().map(OrderService::toResponse).toList();
    }

    public OrderProductResponseDto getUserOrderProductResponseDto(Long orderId, String providerId) {
        return toResponse(findByIdAndProviderId(orderId, providerId));
    }

    public OrderProductResponseDto getStoreOrderProductResponseDto(Long orderId, Long storeId) {
        return toResponse(findByIdAndStoreId(orderId, storeId));
    }

    static OrderProductResponseDto toResponse(Orders order) {
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

    private Orders findByIdAndProviderId(Long orderId, String providerId) {
        return orderRepository.findByIdAndProviderId(orderId, providerId)
                              .orElseThrow(() -> new UsernameNotFoundException("order not found"));
    }

    private Orders findByIdAndStoreId(Long orderId, Long storeId) {
        return orderRepository.findByIdAndStoreId(orderId, storeId)
                              .orElseThrow(() -> new UsernameNotFoundException("order not found"));
    }
}
