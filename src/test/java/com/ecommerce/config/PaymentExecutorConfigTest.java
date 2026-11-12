package com.ecommerce.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * 결정한 설정값 고정:
 * - 결제 스레드 50개 (PG 동시 한도 50, 1000건 결제 60초 이내)
 * - 큐 1000 (한정 수량 1000건 피크를 거절 없이 수용)
 * - 스위퍼 10초 주기
 * - while 루프 디스패처 제거
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentExecutorConfigTest {
    @Autowired
    @Qualifier("paymentExecutor")
    private ThreadPoolTaskExecutor paymentExecutor;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("결제 스레드 풀은 스레드 50개 고정, 큐 1000이다")
    void paymentExecutorSizing() {
        assertThat(paymentExecutor.getCorePoolSize()).isEqualTo(50);
        assertThat(paymentExecutor.getMaxPoolSize()).isEqualTo(50);
        var queue = paymentExecutor.getThreadPoolExecutor().getQueue();
        assertThat(queue.size() + queue.remainingCapacity()).isEqualTo(1000);
    }

    @Test
    @DisplayName("while 루프 디스패처용 스레드 풀은 더 이상 없다")
    void dispatcherExecutorRemoved() {
        assertThat(context.containsBean("paymentDispatcherExecutor")).isFalse();
    }

    @Test
    @DisplayName("스위퍼는 10초 fixedDelay로 스케줄된다")
    void sweeperScheduledEveryTenSeconds() {
        List<FixedDelayTask> sweeperTasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(ScheduledTask::getTask)
                .filter(task -> task instanceof FixedDelayTask)
                .map(task -> (FixedDelayTask) task)
                .filter(task -> task.getRunnable().toString().contains("PaymentSweeper.sweep"))
                .toList();

        assertThat(sweeperTasks).hasSize(1);
        assertThat(sweeperTasks.get(0).getIntervalDuration()).isEqualTo(Duration.ofSeconds(10));
    }
}
