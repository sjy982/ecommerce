package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ecommerce.cart.repository.CartRepository;
import com.ecommerce.category.repository.CategoryRepository;
import com.ecommerce.notification.repository.NotificationRepository;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.model.Orders;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.payment.MockPaymentGateway.Outcome;
import com.ecommerce.payment.job.model.PaymentJobStatus;
import com.ecommerce.payment.job.repository.PaymentJobRepository;
import com.ecommerce.payment.support.PaymentFlowFixture;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.stock.RedisStockRepository;
import com.ecommerce.stock.SaleService;
import com.ecommerce.store.repository.StoreRepository;
import com.ecommerce.support.RedisTestContainer;
import com.ecommerce.user.repository.UserRepository;

/**
 * 결제 실패와 재시도를 주문 접수부터 끝까지 확인한다 (실제 Redis + 전용 H2).
 * 재시도가 빨리 돌도록 스위퍼 주기와 백오프를 짧게, 최대 시도 횟수는 3회로 둔다.
 */
@SpringBootTest(properties = {
        "payment.mock.delay-ms=0",
        "payment.sweeper.initial-delay-ms=100",
        "payment.sweeper.fixed-delay-ms=100",
        "payment.retry.max-attempts=3",
        "payment.retry.backoff-ms=100",
        "spring.datasource.url=jdbc:h2:mem:payment-failure;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentFailureIntegrationTest {
    private static final int STOCK = 10;
    private static final Duration WAIT = Duration.ofSeconds(8);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        RedisTestContainer.register(registry);
    }

    @Autowired private OrderService orderService;
    @Autowired private SaleService saleService;
    @Autowired private RedisStockRepository redisStockRepository;
    @Autowired private RedisTemplate<String, String> redisTemplate;
    @Autowired private MockPaymentGateway gateway;
    @SpyBean private PaymentService paymentService;
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
        fixture.setUp(STOCK);
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        saleService.open(fixture.storeName(), fixture.productId());
    }

    @AfterEach
    void tearDown() {
        fixture.cleanUp();
    }

    private Orders placeOrder() {
        orderService.orderProductAndPaymentRequest(fixture.providerId(), fixture.orderRequest());
        return fixture.orders().get(0);
    }

    private long redisStock() {
        return redisStockRepository.currentStock(fixture.productId());
    }

    @Test
    @DisplayName("확정 실패: 주문은 PAYMENT_FAILED, Redis 재고는 원래대로, job은 삭제, 매출은 0")
    void givenDeclined_whenPaymentRuns_thenFailedAndRestocked() throws InterruptedException {
        gateway.scriptNextNewKey(Outcome.DECLINE);

        Orders order = placeOrder();
        assertThat(redisStock()).isEqualTo(STOCK - 1);   // 주문 접수 시 차감

        assertThat(PaymentFlowFixture.await(() -> !fixture.paymentJobExists(order.getId()), WAIT)).isTrue();
        assertThat(fixture.orderStatus(order.getId())).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(redisStock()).isEqualTo(STOCK);
        assertThat(fixture.totalSales()).isZero();
    }

    @Test
    @DisplayName("이중 결제 방지: PG는 승인했는데 우리 DB 반영이 실패해도, 재시도에서 PG 결제는 1번뿐이다")
    void givenApprovedButCompleteFailsOnce_whenRetried_thenChargedOnce() throws InterruptedException {
        doThrow(new RuntimeException("DB 일시 장애"))
                .doCallRealMethod()
                .when(paymentService).completePayment(anyLong());

        Orders order = placeOrder();

        assertThat(PaymentFlowFixture.await(() -> fixture.orderStatus(order.getId()) == OrderStatus.PAID, WAIT))
                .isTrue();
        assertThat(gateway.chargeCount(PaymentService.paymentKey(order.getId()))).isEqualTo(1);
        assertThat(fixture.totalSales()).isEqualTo(PaymentFlowFixture.PRICE);
        doCallRealMethod().when(paymentService).completePayment(anyLong());
    }

    @Test
    @DisplayName("결과 불명: 승인됐는데 응답이 끊기면, 재시도에서 조회로 승인을 확인하고 다시 결제하지 않는다")
    void givenApprovedButResponseLost_whenRetried_thenPaidWithoutSecondCharge() throws InterruptedException {
        gateway.scriptNextNewKey(Outcome.APPROVE_BUT_LOSE_RESPONSE);

        Orders order = placeOrder();
        String key = PaymentService.paymentKey(order.getId());

        assertThat(PaymentFlowFixture.await(() -> fixture.orderStatus(order.getId()) == OrderStatus.PAID, WAIT))
                .isTrue();
        assertThat(gateway.chargeCount(key)).isEqualTo(1);
        assertThat(redisStock()).isEqualTo(STOCK - 1);   // 결제됐으니 재고는 복구하지 않음
    }

    @Test
    @DisplayName("일시 실패: 처리 전 실패가 이어지다 성공하면 PAID가 되고 결제는 1번이다")
    void givenRetryableThenApproved_whenRetried_thenPaid() throws InterruptedException {
        gateway.scriptNextNewKey(Outcome.FAIL_BEFORE_PROCESSING, Outcome.APPROVE);

        Orders order = placeOrder();
        String key = PaymentService.paymentKey(order.getId());

        assertThat(PaymentFlowFixture.await(() -> fixture.orderStatus(order.getId()) == OrderStatus.PAID, WAIT))
                .isTrue();
        assertThat(gateway.chargeCount(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("최대 횟수까지 처리 전 실패만 나면, PG에 기록이 없음을 확인하고 실패 처리 + 재고 복구")
    void givenAlwaysRetryable_whenExhausted_thenFailedAndRestocked() throws InterruptedException {
        gateway.scriptNextNewKey(Outcome.FAIL_BEFORE_PROCESSING, Outcome.FAIL_BEFORE_PROCESSING,
                Outcome.FAIL_BEFORE_PROCESSING);

        Orders order = placeOrder();

        assertThat(PaymentFlowFixture.await(() -> fixture.orderStatus(order.getId()) == OrderStatus.PAYMENT_FAILED, WAIT))
                .isTrue();
        assertThat(PaymentFlowFixture.await(() -> redisStock() == STOCK, WAIT)).isTrue();
    }

    @Test
    @DisplayName("최대 횟수까지 결과를 끝내 모르면 실패 처리하지 않고 NEEDS_REVIEW로 남긴다 (재고도 그대로)")
    void givenUnknownAndInquiryDown_whenExhausted_thenNeedsReview() throws InterruptedException {
        gateway.scriptNextNewKey(Outcome.LOSE_RESPONSE_UNPROCESSED, Outcome.LOSE_RESPONSE_UNPROCESSED,
                Outcome.LOSE_RESPONSE_UNPROCESSED);
        gateway.failInquiryForNextNewKey();

        Orders order = placeOrder();

        assertThat(PaymentFlowFixture.await(
                () -> fixture.paymentJobStatus(order.getId()) == PaymentJobStatus.NEEDS_REVIEW, WAIT)).isTrue();
        assertThat(fixture.orderStatus(order.getId())).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(redisStock()).isEqualTo(STOCK - 1);
    }

    @Test
    @DisplayName("같은 실패가 다시 처리돼도: 주문 상태 변경은 0행이라 무시되고, 재고 복구도 장부 덕분에 한 번만 된다")
    void givenFailureHandledAgain_whenProcessed_thenRestoredOnce() throws InterruptedException {
        gateway.scriptNextNewKey(Outcome.DECLINE);
        Orders order = placeOrder();
        assertThat(PaymentFlowFixture.await(() -> !fixture.paymentJobExists(order.getId()), WAIT)).isTrue();
        assertThat(redisStock()).isEqualTo(STOCK);

        // ① 같은 주문의 실패 처리가 또 실행됨: 이미 PAYMENT_FAILED라 조건부 UPDATE 0행 → 복구 대상 아님
        Long duplicateJob = fixture.createPaymentJob(order.getId(), PaymentJobStatus.PROCESSING);
        assertThat(paymentService.failPayment(duplicateJob)).isFalse();
        assertThat(fixture.paymentJobExists(order.getId())).isFalse();

        // ② 재고 복구가 또 실행됨(예: 응답이 끊겨 재시도): 스위퍼가 가져가도 장부에 있어 +1 하지 않음
        fixture.createPaymentJob(order.getId(), PaymentJobStatus.RESTOCK_PENDING);
        assertThat(PaymentFlowFixture.await(() -> !fixture.paymentJobExists(order.getId()), WAIT)).isTrue();
        assertThat(redisStock()).isEqualTo(STOCK);
    }
}
