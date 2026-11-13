package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ecommerce.cart.repository.CartRepository;
import com.ecommerce.category.repository.CategoryRepository;
import com.ecommerce.notification.repository.NotificationRepository;
import com.ecommerce.order.DTO.OrderProductResponseDto;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.model.Orders;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.payment.job.repository.PaymentJobRepository;
import com.ecommerce.payment.support.PaymentFlowFixture;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.stock.RedisStockRepository;
import com.ecommerce.stock.SaleService;
import com.ecommerce.stock.exception.NotOnSaleException;
import com.ecommerce.stock.exception.SoldOutException;
import com.ecommerce.store.repository.StoreRepository;
import com.ecommerce.support.RedisTestContainer;
import com.ecommerce.user.repository.UserRepository;

/**
 * 실제 Redis(테스트 컨테이너) + DB로 주문 접수 전체 흐름을 확인한다.
 * - 판매 오픈한 상품만 주문 가능
 * - 재고 판정은 Redis, 주문 접수로 DB의 product.stock은 바뀌지 않음(핫 row 제거)
 * - 재고를 넘는 주문은 DB에 가지 않고 품절
 */
@SpringBootTest(properties = {
        "payment.mock.delay-ms=0",
        "spring.datasource.url=jdbc:h2:mem:order-stock;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
class OrderStockIntegrationTest {
    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        RedisTestContainer.register(registry);
    }

    @Autowired private OrderService orderService;
    @Autowired private SaleService saleService;
    @Autowired private RedisStockRepository redisStockRepository;
    @Autowired private RedisTemplate<String, String> redisTemplate;
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
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();   // 테스트 전용 Redis
        fixture = new PaymentFlowFixture(cartRepository, userRepository, storeRepository, categoryRepository,
                productRepository, orderRepository, paymentJobRepository, notificationRepository);
        fixture.setUp(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        PaymentFlowFixture.await(() -> fixture.orders().stream()
                .allMatch(o -> o.getStatus() == OrderStatus.PAID), Duration.ofSeconds(10));
        fixture.cleanUp();
    }

    @Test
    @DisplayName("판매 오픈한 상품을 주문하면 접수되고, Redis 재고만 줄고 DB의 product.stock은 그대로다")
    void givenOpenedSale_whenOrder_thenAcceptedAndOnlyRedisDecreased() {
        saleService.open(fixture.storeName(), fixture.productId());

        OrderProductResponseDto response =
                orderService.orderProductAndPaymentRequest(fixture.providerId(), fixture.orderRequest());

        assertThat(response.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        Orders order = fixture.orders().get(0);
        assertThat(order.getRequestId()).isNotBlank();
        assertThat(redisStockRepository.currentStock(fixture.productId())).isEqualTo(1L);
        assertThat(fixture.productStockInDb()).isEqualTo(2);
    }

    @Test
    @DisplayName("재고 2개에 3번 주문하면 세 번째는 품절이고 DB에는 주문이 2건만 있다")
    void givenTwoStock_whenThreeOrders_thenThirdSoldOut() {
        saleService.open(fixture.storeName(), fixture.productId());
        orderService.orderProductAndPaymentRequest(fixture.providerId(), fixture.orderRequest());
        orderService.orderProductAndPaymentRequest(fixture.providerId(), fixture.orderRequest());

        assertThatThrownBy(() -> orderService.orderProductAndPaymentRequest(fixture.providerId(), fixture.orderRequest()))
                .isInstanceOf(SoldOutException.class);
        assertThat(fixture.orders()).hasSize(2);
        assertThat(redisStockRepository.currentStock(fixture.productId())).isEqualTo(0L);
    }

    @Test
    @DisplayName("판매 오픈 전인 상품은 주문할 수 없고 DB에 아무것도 남지 않는다")
    void givenNotOpened_whenOrder_thenNotOnSale() {
        assertThatThrownBy(() -> orderService.orderProductAndPaymentRequest(fixture.providerId(), fixture.orderRequest()))
                .isInstanceOf(NotOnSaleException.class);
        assertThat(fixture.orders()).isEmpty();
    }
}
