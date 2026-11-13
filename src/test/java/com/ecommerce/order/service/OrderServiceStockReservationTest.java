package com.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ecommerce.order.DTO.OrderProductRequestDto;
import com.ecommerce.order.DTO.OrderProductResponseDto;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.stock.RedisStockRepository;
import com.ecommerce.stock.StockReserveResult;
import com.ecommerce.stock.exception.NotOnSaleException;
import com.ecommerce.stock.exception.SoldOutException;
import com.ecommerce.stock.exception.StockUnavailableException;

/**
 * 주문 접수 흐름: Redis 차감 → DB 저장 → (실패 시) 저장 안 된 게 확실할 때만 Redis 되돌림.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceStockReservationTest {
    private static final String PROVIDER_ID = "provider";
    private static final long PRODUCT_ID = 7L;
    private static final int QUANTITY = 2;

    @Mock private RedisStockRepository redisStockRepository;
    @Mock private OrderCreator orderCreator;
    @Mock private OrderRepository orderRepository;

    @InjectMocks private OrderService orderService;

    private final OrderProductRequestDto request = OrderProductRequestDto.builder()
            .productId(PRODUCT_ID).quantity(QUANTITY).deliveryAddress("addr").phoneNumber("010-1234-1234").build();

    @Test
    @DisplayName("품절이면 DB에 가지 않고 품절 예외")
    void givenSoldOut_whenOrder_thenSoldOutWithoutDb() {
        when(redisStockRepository.reserve(PRODUCT_ID, QUANTITY)).thenReturn(StockReserveResult.SOLD_OUT);

        assertThatThrownBy(() -> orderService.orderProductAndPaymentRequest(PROVIDER_ID, request))
                .isInstanceOf(SoldOutException.class);
        verify(orderCreator, never()).create(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("판매 오픈 전이면 DB에 가지 않고 판매 중 아님 예외")
    void givenNotOnSale_whenOrder_thenNotOnSaleWithoutDb() {
        when(redisStockRepository.reserve(PRODUCT_ID, QUANTITY)).thenReturn(StockReserveResult.NOT_ON_SALE);

        assertThatThrownBy(() -> orderService.orderProductAndPaymentRequest(PROVIDER_ID, request))
                .isInstanceOf(NotOnSaleException.class);
        verify(orderCreator, never()).create(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Redis 장애면 주문을 받지 않는다 (DB에 가지 않음)")
    void givenRedisDown_whenOrder_thenStockUnavailableWithoutDb() {
        when(redisStockRepository.reserve(PRODUCT_ID, QUANTITY))
                .thenThrow(new StockUnavailableException(new RuntimeException("down")));

        assertThatThrownBy(() -> orderService.orderProductAndPaymentRequest(PROVIDER_ID, request))
                .isInstanceOf(StockUnavailableException.class);
        verify(orderCreator, never()).create(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("차감 성공 후 DB 저장까지 끝나면 접수 응답을 주고 되돌리지 않는다")
    void givenReserved_whenSaved_thenReturnsWithoutRelease() {
        OrderProductResponseDto saved = OrderProductResponseDto.builder().build();
        when(redisStockRepository.reserve(PRODUCT_ID, QUANTITY)).thenReturn(StockReserveResult.RESERVED);
        when(orderCreator.create(eq(PROVIDER_ID), eq(request), anyString())).thenReturn(saved);

        OrderProductResponseDto response = orderService.orderProductAndPaymentRequest(PROVIDER_ID, request);

        assertThat(response).isSameAs(saved);
        verify(redisStockRepository, never()).release(anyLong(), anyInt());
    }

    @Test
    @DisplayName("DB 저장이 실패했고 주문이 없으면 Redis 재고를 되돌린다 (같은 requestId로 확인)")
    void givenDbFailedAndNotSaved_whenOrder_thenRelease() {
        when(redisStockRepository.reserve(PRODUCT_ID, QUANTITY)).thenReturn(StockReserveResult.RESERVED);
        when(orderCreator.create(eq(PROVIDER_ID), eq(request), anyString()))
                .thenThrow(new RuntimeException("DB down"));
        when(orderRepository.existsByRequestId(anyString())).thenReturn(false);

        assertThatThrownBy(() -> orderService.orderProductAndPaymentRequest(PROVIDER_ID, request))
                .hasMessage("DB down");

        ArgumentCaptor<String> createdWith = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> checkedWith = ArgumentCaptor.forClass(String.class);
        verify(orderCreator).create(eq(PROVIDER_ID), eq(request), createdWith.capture());
        verify(orderRepository).existsByRequestId(checkedWith.capture());
        assertThat(checkedWith.getValue()).isNotBlank().isEqualTo(createdWith.getValue());
        verify(redisStockRepository).release(PRODUCT_ID, QUANTITY);
    }

    @Test
    @DisplayName("예외가 났지만 실제로는 커밋된 경우(결과 불명)에는 되돌리지 않는다 → 초과 판매 방지")
    void givenDbErrorButActuallyCommitted_whenOrder_thenNoRelease() {
        when(redisStockRepository.reserve(PRODUCT_ID, QUANTITY)).thenReturn(StockReserveResult.RESERVED);
        when(orderCreator.create(eq(PROVIDER_ID), eq(request), anyString()))
                .thenThrow(new RuntimeException("commit response lost"));
        when(orderRepository.existsByRequestId(anyString())).thenReturn(true);

        assertThatThrownBy(() -> orderService.orderProductAndPaymentRequest(PROVIDER_ID, request))
                .isInstanceOf(RuntimeException.class);
        verify(redisStockRepository, never()).release(anyLong(), anyInt());
    }

    @Test
    @DisplayName("저장 여부조차 확인할 수 없으면 되돌리지 않는다 → 덜 팔림 쪽으로 남김")
    void givenExistenceCheckFails_whenOrder_thenNoReleaseAndOriginalErrorThrown() {
        when(redisStockRepository.reserve(PRODUCT_ID, QUANTITY)).thenReturn(StockReserveResult.RESERVED);
        when(orderCreator.create(eq(PROVIDER_ID), eq(request), anyString()))
                .thenThrow(new RuntimeException("DB down"));
        when(orderRepository.existsByRequestId(anyString())).thenThrow(new RuntimeException("still down"));

        assertThatThrownBy(() -> orderService.orderProductAndPaymentRequest(PROVIDER_ID, request))
                .hasMessage("DB down");
        verify(redisStockRepository, never()).release(anyLong(), anyInt());
    }
}
