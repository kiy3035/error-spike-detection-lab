package dev.errordetection.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    /**
     * 저장과 집계에서 공유할 UTC 시스템 시계를 제공합니다.
     *
     * @return UTC 시스템 시계
     */
    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }
}
