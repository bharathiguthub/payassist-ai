package com.payassist.refund.internal;

import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

    /** Until refund requests exist (milestone 4), nothing can be reserved. */
    @Bean
    @ConditionalOnMissingBean(RefundReservations.class)
    RefundReservations noPendingRefundReservations() {
        return paymentRef -> BigDecimal.ZERO;
    }
}
