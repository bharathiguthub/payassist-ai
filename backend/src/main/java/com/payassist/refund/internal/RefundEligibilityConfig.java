package com.payassist.refund.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RefundPolicy.class)
class RefundEligibilityConfig {

    @Bean
    RefundEligibilityEngine refundEligibilityEngine(RefundPolicy policy) {
        return new RefundEligibilityEngine(policy);
    }
}
