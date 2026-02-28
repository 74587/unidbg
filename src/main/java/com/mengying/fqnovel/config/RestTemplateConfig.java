package com.mengying.fqnovel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(FQDownloadProperties downloadProperties) {
        Duration connectTimeout = safeTimeout(downloadProperties.getUpstreamConnectTimeoutMs(), Duration.ofSeconds(8));
        Duration readTimeout = safeTimeout(downloadProperties.getUpstreamReadTimeoutMs(), Duration.ofSeconds(15));

        // 使用 JDK HttpClient，请求连接可复用，避免高频章节请求下频繁建连。
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }

    private Duration safeTimeout(long valueMs, Duration defaultValue) {
        if (valueMs <= 0) {
            return defaultValue;
        }
        if (valueMs > Integer.MAX_VALUE) {
            return Duration.ofMillis(Integer.MAX_VALUE);
        }
        return Duration.ofMillis(valueMs);
    }

}
