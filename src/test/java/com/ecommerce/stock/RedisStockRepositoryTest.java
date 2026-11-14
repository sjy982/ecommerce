package com.ecommerce.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.ecommerce.stock.exception.StockUnavailableException;
import com.ecommerce.support.RedisTestContainer;

/**
 * 재고 판정의 핵심. 실제 Redis(테스트 컨테이너)에서 Lua 스크립트의 원자성을 검증한다.
 */
class RedisStockRepositoryTest {
    private static final Long PRODUCT_ID = 1L;

    private LettuceConnectionFactory connectionFactory;
    private RedisTemplate<String, String> redisTemplate;
    private RedisStockRepository repository;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(RedisTestContainer.host(), RedisTestContainer.port());
        connectionFactory.afterPropertiesSet();
        redisTemplate = template(connectionFactory);
        repository = new RedisStockRepository(redisTemplate);
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    private static RedisTemplate<String, String> template(LettuceConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Test
    @DisplayName("재고 1개에 두 번 차감하면 첫 번째만 성공하고 재고는 0이 된다 (음수 불가)")
    void givenOneStock_whenReserveTwice_thenSecondSoldOut() {
        repository.open(PRODUCT_ID, 1);

        assertThat(repository.reserve(PRODUCT_ID, 1)).isEqualTo(StockReserveResult.RESERVED);
        assertThat(repository.reserve(PRODUCT_ID, 1)).isEqualTo(StockReserveResult.SOLD_OUT);
        assertThat(repository.currentStock(PRODUCT_ID)).isEqualTo(0L);
    }

    @Test
    @DisplayName("남은 수량보다 많이 주문하면 품절이고 재고는 그대로다")
    void givenStock_whenReserveMoreThanStock_thenSoldOutAndUnchanged() {
        repository.open(PRODUCT_ID, 2);

        assertThat(repository.reserve(PRODUCT_ID, 3)).isEqualTo(StockReserveResult.SOLD_OUT);
        assertThat(repository.currentStock(PRODUCT_ID)).isEqualTo(2L);
    }

    @Test
    @DisplayName("판매 오픈하지 않은(재고 키가 없는) 상품은 판매 중 아님이다")
    void givenNotOpened_whenReserve_thenNotOnSale() {
        assertThat(repository.reserve(PRODUCT_ID, 1)).isEqualTo(StockReserveResult.NOT_ON_SALE);
        assertThat(repository.currentStock(PRODUCT_ID)).isNull();
    }

    @Test
    @DisplayName("재고 100개에 500개 스레드가 동시에 차감해도 정확히 100건만 성공한다")
    void givenHundredStock_whenReserveConcurrently_thenExactlyHundredReserved() throws Exception {
        repository.open(PRODUCT_ID, 100);
        int requests = 500;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<StockReserveResult>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return repository.reserve(PRODUCT_ID, 1);
                }));
            }
            start.countDown();

            int reserved = 0;
            int soldOut = 0;
            for (Future<StockReserveResult> result : results) {
                StockReserveResult r = result.get(10, TimeUnit.SECONDS);
                if (r == StockReserveResult.RESERVED) {
                    reserved++;
                } else if (r == StockReserveResult.SOLD_OUT) {
                    soldOut++;
                }
            }

            assertThat(reserved).isEqualTo(100);
            assertThat(soldOut).isEqualTo(400);
            assertThat(repository.currentStock(PRODUCT_ID)).isEqualTo(0L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("되돌리기(release)는 판매 중인 상품의 재고를 늘린다")
    void givenOnSale_whenRelease_thenIncreased() {
        repository.open(PRODUCT_ID, 1);
        repository.reserve(PRODUCT_ID, 1);

        repository.release(PRODUCT_ID, 1);

        assertThat(repository.currentStock(PRODUCT_ID)).isEqualTo(1L);
    }

    @Test
    @DisplayName("판매 중이 아닌 상품에 되돌리기를 해도 재고 키를 새로 만들지 않는다 (판매 중으로 바뀌면 안 됨)")
    void givenNotOnSale_whenRelease_thenKeyNotCreated() {
        repository.release(PRODUCT_ID, 1);

        assertThat(repository.currentStock(PRODUCT_ID)).isNull();
    }

    @Test
    @DisplayName("Redis에 연결할 수 없으면 재고 판정 불가 예외를 던진다")
    void givenRedisDown_whenReserve_thenStockUnavailable() {
        RedisStandaloneConfiguration unreachable = new RedisStandaloneConfiguration("localhost", 1);
        LettuceConnectionFactory downFactory = new LettuceConnectionFactory(unreachable,
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        downFactory.afterPropertiesSet();
        try {
            RedisStockRepository down = new RedisStockRepository(template(downFactory));

            assertThatThrownBy(() -> down.reserve(PRODUCT_ID, 1)).isInstanceOf(StockUnavailableException.class);
        } finally {
            downFactory.destroy();
        }
    }

    @Test
    @DisplayName("결제 실패 재고 복구: 같은 주문으로 여러 번 복구해도 재고는 한 번만 늘어난다")
    void givenSameOrder_whenRestoreTwice_thenIncreasedOnce() {
        repository.open(PRODUCT_ID, 10);

        StockRestoreResult first = repository.restore(PRODUCT_ID, 42L, 1);
        StockRestoreResult second = repository.restore(PRODUCT_ID, 42L, 1);

        assertThat(first).isEqualTo(StockRestoreResult.RESTORED);
        assertThat(second).isEqualTo(StockRestoreResult.ALREADY_RESTORED);
        assertThat(repository.currentStock(PRODUCT_ID)).isEqualTo(11L);
    }

    @Test
    @DisplayName("결제 실패 재고 복구: 다른 주문은 각각 복구된다")
    void givenDifferentOrders_whenRestore_thenEachIncreased() {
        repository.open(PRODUCT_ID, 10);

        repository.restore(PRODUCT_ID, 42L, 1);
        repository.restore(PRODUCT_ID, 43L, 2);

        assertThat(repository.currentStock(PRODUCT_ID)).isEqualTo(13L);
    }

    @Test
    @DisplayName("결제 실패 재고 복구: 판매 중이 아니면(키 없음) 아무것도 만들지 않는다")
    void givenNotOnSale_whenRestore_thenNothingCreated() {
        StockRestoreResult result = repository.restore(PRODUCT_ID, 42L, 1);

        assertThat(result).isEqualTo(StockRestoreResult.NOT_ON_SALE);
        assertThat(repository.currentStock(PRODUCT_ID)).isNull();
    }

    @Test
    @DisplayName("판매를 다시 열면 DB 기준으로 재적재하므로 복구 장부도 비운다")
    void givenRestoredOrder_whenReopen_thenLedgerCleared() {
        repository.open(PRODUCT_ID, 10);
        repository.restore(PRODUCT_ID, 42L, 1);

        repository.open(PRODUCT_ID, 5);
        StockRestoreResult result = repository.restore(PRODUCT_ID, 42L, 1);

        assertThat(result).isEqualTo(StockRestoreResult.RESTORED);
        assertThat(repository.currentStock(PRODUCT_ID)).isEqualTo(6L);
    }
}
