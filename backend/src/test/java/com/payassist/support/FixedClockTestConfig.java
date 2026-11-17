package com.payassist.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Integration tests run at a fixed instant. The synthetic seed data has absolute dates (January to
 * October 2026), so time-based rules (refund windows, investigation thresholds) must be evaluated against
 * a fixed "now" or tests would start failing as real time passes.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockTestConfig {

    public static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

    @Bean
    @Primary
    Clock fixedTestClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
