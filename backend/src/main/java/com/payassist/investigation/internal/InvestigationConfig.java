package com.payassist.investigation.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvestigationProperties.class)
class InvestigationConfig {

    @Bean
    InvestigationSummaryBuilder investigationSummaryBuilder(InvestigationProperties properties) {
        return new InvestigationSummaryBuilder(properties);
    }
}
