package com.ecommerce.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.model.Orders;

public interface OrderRepository extends JpaRepository<Orders, Long> {
    List<Orders> findAllByUserProviderIdOrderByOrderDateDesc(String providerId);
    List<Orders> findAllByStoreIdOrderByOrderDateDesc(Long storeId);

    @Query("SELECT o FROM Orders o JOIN o.user u WHERE u.providerId = :providerId AND o.id = :orderId")
    Optional<Orders> findByIdAndProviderId(@Param("orderId") Long orderId, @Param("providerId") String providerId);

    @Query("SELECT o FROM Orders o JOIN o.store s WHERE s.id = :storeId AND o.id = :orderId")
    Optional<Orders> findByIdAndStoreId(@Param("orderId") Long orderId, @Param("storeId") Long storeId);

    boolean existsByRequestId(String requestId);

    /** 해당 상품에서 excludedStatus가 아닌 주문들의 수량 합 (판매 오픈 시 남은 재고 계산용) */
    @Query("SELECT COALESCE(SUM(o.quantity), 0) FROM Orders o WHERE o.product.id = :productId AND o.status <> :excludedStatus")
    long sumQuantityByProductIdAndStatusNot(@Param("productId") Long productId,
                                            @Param("excludedStatus") OrderStatus excludedStatus);
}
