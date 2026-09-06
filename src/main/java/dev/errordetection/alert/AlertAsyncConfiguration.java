package dev.errordetection.alert;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@EnableAsync
@EnableRetry
@Configuration(proxyBeanMethods = false)
public class AlertAsyncConfiguration {

    /**
     * 명시적 크기와 유한 queue 및 호출자 실행 거부 정책을 가진 알림 executor를 구성합니다.
     *
     * @param properties 알림 executor 설정
     * @return bounded 비동기 executor
     */
    @Bean("alertExecutor")
    ThreadPoolTaskExecutor alertExecutor(AlertProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.executor().coreSize());
        executor.setMaxPoolSize(properties.executor().maxSize());
        executor.setQueueCapacity(properties.executor().queueCapacity());
        executor.setThreadNamePrefix("alert-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
