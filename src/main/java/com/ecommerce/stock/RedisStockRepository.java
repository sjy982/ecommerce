package com.ecommerce.stock;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.ecommerce.stock.exception.StockUnavailableException;

/**
 * 판매 중 재고의 판정기. 진실(원본)은 DB이고, 이 값은 언제든 DB로 다시 계산할 수 있다.
 * 키: stock:product:{productId}  값: 남은 판매 가능 수량
 */
@Component
public class RedisStockRepository {
    private static final String KEY_PREFIX = "stock:product:";
    /** 결제 실패로 재고를 복구한 주문 ID 장부 (Set) */
    private static final String RELEASED_PREFIX = "stock:released:";

    /**
     * 확인 + 차감을 한 번에(원자적으로) 한다. Redis는 스크립트 실행 중 다른 명령을 끼워 넣지 않는다.
     * -1: 키 없음(판매 중 아님) / 0: 수량 부족(품절, 값은 그대로) / 1: 차감 성공
     * GET 후 DECR처럼 명령을 나누면 그 사이에 다른 요청이 끼어들어 초과 판매가 생긴다.
     */
    private static final RedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local stock = redis.call('GET', KEYS[1])
            if not stock then
              return -1
            end
            local quantity = tonumber(ARGV[1])
            if tonumber(stock) < quantity then
              return 0
            end
            redis.call('DECRBY', KEYS[1], quantity)
            return 1
            """, Long.class);

    /** 판매 중(키가 있을 때)만 늘린다. 키를 새로 만들면 판매 중이 아닌 상품이 판매 중으로 바뀌어 버린다. */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
              return 1
            end
            return 0
            """, Long.class);

    /**
     * 결제 실패 재고 복구 (멱등). 장부에 주문 ID를 처음 넣을 때만 늘린다.
     * -1: 판매 중 아님(키 없음) / 0: 이미 복구한 주문 / 1: 이번에 복구
     * SADD와 INCRBY를 앱에서 따로 보내면, SADD 직후 죽었을 때 재시도가 "이미 복구됨"으로 보고 영영 복구하지 않는다.
     * 그래서 한 스크립트 안에서 함께 실행한다.
     */
    private static final RedisScript<Long> RESTORE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return -1
            end
            if redis.call('SADD', KEYS[2], ARGV[1]) == 0 then
              return 0
            end
            redis.call('INCRBY', KEYS[1], tonumber(ARGV[2]))
            return 1
            """, Long.class);

    /** 판매 오픈: DB 기준 수량으로 덮어쓰고, 이전 복구 장부는 지운다 (재계산 값에 이미 반영돼 있음). */
    private static final RedisScript<Long> OPEN = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1])
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    public RedisStockRepository(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public StockReserveResult reserve(Long productId, int quantity) {
        Long result = run(() -> redisTemplate.execute(RESERVE, List.of(key(productId)), String.valueOf(quantity)));
        if (result == null || result == -1L) {
            return StockReserveResult.NOT_ON_SALE;
        }
        return result == 1L ? StockReserveResult.RESERVED : StockReserveResult.SOLD_OUT;
    }

    public void release(Long productId, int quantity) {
        run(() -> redisTemplate.execute(RELEASE, List.of(key(productId)), String.valueOf(quantity)));
    }

    /** 결제가 최종 실패한 주문의 재고를 되돌린다. 같은 주문으로 여러 번 불러도 한 번만 늘어난다. */
    public StockRestoreResult restore(Long productId, Long orderId, int quantity) {
        Long result = run(() -> redisTemplate.execute(RESTORE, List.of(key(productId), releasedKey(productId)),
                String.valueOf(orderId), String.valueOf(quantity)));
        if (result == null || result == -1L) {
            return StockRestoreResult.NOT_ON_SALE;
        }
        return result == 1L ? StockRestoreResult.RESTORED : StockRestoreResult.ALREADY_RESTORED;
    }

    /** 판매 오픈: DB 기준으로 계산한 수량을 그대로 넣는다. */
    public void open(Long productId, long stock) {
        run(() -> redisTemplate.execute(OPEN, List.of(key(productId), releasedKey(productId)), String.valueOf(stock)));
    }

    /** @return 남은 수량, 판매 중이 아니면 null */
    public Long currentStock(Long productId) {
        String value = run(() -> redisTemplate.opsForValue().get(key(productId)));
        return value == null ? null : Long.valueOf(value);
    }

    private static String key(Long productId) {
        return KEY_PREFIX + productId;
    }

    private static String releasedKey(Long productId) {
        return RELEASED_PREFIX + productId;
    }

    /** Redis 접근 실패(연결 실패, 타임아웃 등)는 "재고를 판정할 수 없음"으로 바꿔 주문을 막는다. */
    private <T> T run(RedisCall<T> call) {
        try {
            return call.execute();
        } catch (DataAccessException e) {
            throw new StockUnavailableException(e);
        }
    }

    @FunctionalInterface
    private interface RedisCall<T> {
        T execute();
    }
}
