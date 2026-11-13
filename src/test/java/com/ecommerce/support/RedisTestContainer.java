package com.ecommerce.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 전용 Redis 컨테이너 (JVM당 1개, 처음 쓸 때 기동).
 * 개발자 로컬 Redis(6379)의 데이터를 건드리지 않기 위해 사용한다. Docker가 필요하다.
 */
public final class RedisTestContainer {
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private RedisTestContainer() {
    }

    public static String host() {
        return REDIS.getHost();
    }

    public static int port() {
        return REDIS.getMappedPort(6379);
    }

    /** @DynamicPropertySource에서 호출해 스프링 컨텍스트가 이 컨테이너를 쓰게 한다. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", RedisTestContainer::host);
        registry.add("spring.data.redis.port", RedisTestContainer::port);
    }
}
