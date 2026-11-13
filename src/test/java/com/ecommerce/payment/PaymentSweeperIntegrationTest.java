package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.ecommerce.cart.repository.CartRepository;
import com.ecommerce.category.repository.CategoryRepository;
import com.ecommerce.notification.repository.NotificationRepository;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.model.Orders;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.payment.job.repository.PaymentJobRepository;
import com.ecommerce.payment.support.PaymentFlowFixture;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.store.repository.StoreRepository;
import com.ecommerce.user.repository.UserRepository;

/**
 * 안전망: 빠른 경로를 타지 못한(거절·예외 등으로 놓친) PENDING job도
 * 스위퍼가 주기적으로 다시 꺼내 결제를 끝낸다.
 * 스위퍼 주기를 짧게 바꾼 전용 컨텍스트라 테스트가 끝나면 닫는다(다른 테스트의 job을 건드리지 않도록).
 */
@SpringBootTest(properties = {
        "payment.mock.delay-ms=0",
        "payment.sweeper.initial-delay-ms=100",
        "payment.sweeper.fixed-delay-ms=200",
        // 컨텍스트를 닫을 때 create-drop이 공유 H2의 테이블을 지우지 않도록 전용 DB 사용
        "spring.datasource.url=jdbc:h2:mem:payment-sweeper;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentSweeperIntegrationTest {
    @Autowired private CartRepository cartRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentJobRepository paymentJobRepository;
    @Autowired private NotificationRepository notificationRepository;

    private PaymentFlowFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new PaymentFlowFixture(cartRepository, userRepository, storeRepository, categoryRepository,
                productRepository, orderRepository, paymentJobRepository, notificationRepository);
        fixture.setUp(10);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanUp();
    }

    @Test
    @DisplayName("빠른 경로에서 놓친 PENDING job도 스위퍼가 가져가 PAID로 만든다")
    void givenMissedPendingJob_whenSweeperRuns_thenPaid() throws InterruptedException {
        Orders order = fixture.createMissedPendingOrder();

        boolean paid = PaymentFlowFixture.await(
                () -> fixture.orderStatus(order.getId()) == OrderStatus.PAID, Duration.ofSeconds(5));

        assertThat(paid).as("스위퍼가 놓친 job을 처리해야 한다").isTrue();
        assertThat(fixture.totalSales()).isEqualTo(PaymentFlowFixture.PRICE);
    }
}
