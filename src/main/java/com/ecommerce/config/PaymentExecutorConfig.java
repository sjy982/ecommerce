package com.ecommerce.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableScheduling
public class PaymentExecutorConfig {
    /**
     * 결제 워커 스레드 풀.
     * - 스레드 50: PG 동시 요청 한도 50 가정. 결제 1건 3초 → 초당 약 16.7건, 1000건을 60초 안에 결제 완료.
     * - core = max: 큐가 꽉 차야 max까지 늘어나므로 큰 큐에서는 max를 따로 둘 의미가 없다.
     * - 큐 1000: 한정 수량 1000건이 한꺼번에 접수돼도 거절 없이 수용. 넘치면 job은 PENDING으로 남고 스위퍼가 처리한다.
     * - 종료 시 큐에 남은 결제를 최대 60초 동안 마저 처리하고 내려간다.
     */
    @Bean(name = "paymentExecutor")
    public ThreadPoolTaskExecutor paymentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(50);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("payment-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
