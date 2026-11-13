package com.ecommerce.stock;

import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.service.ProductService;

import lombok.RequiredArgsConstructor;

/**
 * 판매 오픈: 상품의 판매 가능 수량을 Redis에 적재한다.
 * 실무처럼 상품 등록 시점이 아니라 판매(이벤트)를 여는 시점에 적재한다.
 *
 * - 항상 DB 기준으로 계산한다: 남은 수량 = product.stock(판매 총수량) − 결제 실패가 아닌 주문 수량 합
 * - Redis가 재시작돼 값이 사라졌을 때도 이걸 다시 호출해 복구한다 (스냅샷 값을 믿지 않음)
 * - 판매가 진행 중일 때 다시 호출하면 안 된다: 커밋 직전인 주문을 빼지 못해 초과 판매가 생길 수 있다
 */
@Service
@RequiredArgsConstructor
public class SaleService {
    private final ProductService productService;
    private final OrderRepository orderRepository;
    private final RedisStockRepository redisStockRepository;

    @Transactional(readOnly = true)
    public long open(String storeName, Long productId) {
        Product product = productService.findById(productId);
        if (!product.getStore().getName().equals(storeName)) {
            throw new UsernameNotFoundException("product not found");   // 남의 상품 존재 여부를 드러내지 않음
        }
        long sold = orderRepository.sumQuantityByProductIdAndStatusNot(productId, OrderStatus.PAYMENT_FAILED);
        long remaining = Math.max(0, product.getStock() - sold);
        redisStockRepository.open(productId, remaining);
        return remaining;
    }
}
