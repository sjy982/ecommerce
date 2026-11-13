package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ecommerce.cart.repository.CartRepository;
import com.ecommerce.category.repository.CategoryRepository;
import com.ecommerce.notification.repository.NotificationRepository;
import com.ecommerce.order.DTO.OrderProductResponseDto;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.model.Orders;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.payment.job.model.PaymentJob;
import com.ecommerce.payment.job.model.PaymentJobStatus;
import com.ecommerce.payment.job.repository.PaymentJobRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ecommerce.payment.support.PaymentFlowFixture;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.store.repository.StoreRepository;
import com.ecommerce.stock.SaleService;
import com.ecommerce.support.RedisTestContainer;
import com.ecommerce.user.repository.UserRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 빠른 경로: 주문 트랜잭션 커밋 → AFTER_COMMIT 이벤트 → 선점 → 결제 스레드 풀.
 * 스위퍼는 test 프로필에서 사실상 꺼져 있으므로, PAID가 되면 빠른 경로가 처리한 것이다.
 */
@SpringBootTest(properties = {
        "payment.mock.delay-ms=0",
        // 커밋이 필요한 테스트라 다른 테스트와 같은 H2를 공유하지 않도록 전용 DB 사용
        "spring.datasource.url=jdbc:h2:mem:payment-fast-path;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
class PaymentFastPathIntegrationTest {
    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        RedisTestContainer.register(registry);   // 주문 접수가 Redis 재고 판정을 거치므로 테스트 전용 Redis 사용
    }

    @Autowired private SaleService saleService;
    @Autowired private RedisTemplate<String, String> redisTemplate;
    @Autowired private OrderService orderService;
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
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        saleService.open(fixture.storeName(), fixture.productId());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return;   // @Transactional 테스트는 롤백으로 정리된다
        }
        PaymentFlowFixture.await(() -> fixture.orders().stream()
                .allMatch(o -> o.getStatus() == OrderStatus.PAID), Duration.ofSeconds(10));
        fixture.cleanUp();
    }

    @Test
    @Transactional   // 롤백되므로 커밋 이벤트는 발생하지 않는다 → 주문 트랜잭션 안의 상태만 확인
    @DisplayName("결제 job은 주문 트랜잭션 안에서 이미 선점된 상태(PROCESSING)로 만들어진다")
    void givenOrder_whenCreated_thenPaymentJobBornClaimed() {
        orderService.orderProductAndPaymentRequest(fixture.providerId(), fixture.orderRequest());

        Orders order = fixture.orders().get(0);
        PaymentJob job = paymentJobRepository.findAll().stream()
                .filter(j -> order.getId().equals(j.getOrderId()))
                .findFirst().orElseThrow();
        assertThat(job.getStatus()).isEqualTo(PaymentJobStatus.PROCESSING);
    }

    @Test
    @DisplayName("주문 접수 응답은 PENDING_PAYMENT이고, 커밋 직후 빠른 경로로 결제되어 PAID가 된다")
    void givenOrder_whenCommitted_thenPaidByFastPath() throws InterruptedException {
        OrderProductResponseDto response =
                orderService.orderProductAndPaymentRequest(fixture.providerId(), fixture.orderRequest());

        assertThat(response.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        Orders order = fixture.orders().get(0);

        boolean paid = PaymentFlowFixture.await(
                () -> fixture.orderStatus(order.getId()) == OrderStatus.PAID, Duration.ofSeconds(8));

        assertThat(paid).as("빠른 경로로 결제가 끝나 PAID가 되어야 한다").isTrue();
        assertThat(fixture.totalSales()).isEqualTo(PaymentFlowFixture.PRICE);
        assertThat(PaymentFlowFixture.await(() -> !fixture.paymentJobExists(order.getId()), Duration.ofSeconds(2)))
                .as("결제 완료 후 job은 삭제된다").isTrue();
    }
}
