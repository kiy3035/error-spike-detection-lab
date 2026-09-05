package dev.errordetection.infrastructure.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class NotificationEndpointConfiguration {

    /**
     * 로컬 가짜 알림 서버에 접근할 때 사용할 HTTP 클라이언트를 구성합니다.
     *
     * @param builder Spring이 제공하는 HTTP 클라이언트 빌더
     * @param properties 가짜 알림 서버 연결 설정
     * @return timeout이 적용된 HTTP 클라이언트
     */
    @Bean
    RestClient notificationRestClient(
            RestClient.Builder builder,
            NotificationEndpointProperties properties
    ) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
