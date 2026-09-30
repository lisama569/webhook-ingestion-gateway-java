package com.github.lisama569.webhookingestiongatewayjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 网关装配：开启配置绑定并提供可替换的时钟（测试可注入固定时钟）。
 */
@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
public class GatewayConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
