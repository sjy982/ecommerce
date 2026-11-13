package com.ecommerce.product.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ecommerce.product.model.Product;

/**
 * product.stock은 판매 총수량이다. 판매 중 재고 판정·차감은 Redis가 하고(RedisStockRepository),
 * 주문마다 이 row를 UPDATE하지 않는다(핫 row 락 제거).
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
    boolean existsById(long productId);
    Optional<Product> findById(long id);
}
