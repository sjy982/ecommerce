package com.ecommerce.order.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

import com.ecommerce.product.model.Product;
import com.ecommerce.store.model.Store;
import com.ecommerce.user.model.Users;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Orders {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "userId", nullable = false)
    private Users user;

    @ManyToOne
    @JoinColumn(name = "storeId", nullable = false)
    private Store store;

    @ManyToOne
    @JoinColumn(name = "productId", nullable = false)
    private Product product;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private LocalDateTime orderDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "VARCHAR(30) CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'PAYMENT_FAILED'))")
    private OrderStatus status;

    @Column(nullable = false)
    private String deliveryAddress;

    @Column(nullable = false, length = 15)
    private String phoneNumber;

    /**
     * 주문 요청마다 서버가 만드는 식별자(UUID).
     * DB 저장 중 예외가 나면 이 값으로 "실제로 저장됐는지"를 확인해, 저장되지 않은 게 확실할 때만 Redis 재고를 되돌린다.
     */
    @Column(unique = true, length = 36)
    private String requestId;

    @PrePersist
    public void prePersist() {
        orderDate = (orderDate == null) ? LocalDateTime.now() : orderDate;
        status = (status == null) ? OrderStatus.PENDING_PAYMENT : status;
    }

    public void markPaid() {
        status = OrderStatus.PAID;
    }

    public void markFailed() {
        status = OrderStatus.PAYMENT_FAILED;
    }
}
