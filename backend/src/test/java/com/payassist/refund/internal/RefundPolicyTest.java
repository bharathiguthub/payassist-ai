package com.payassist.refund.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payassist.payment.PaymentMethod;
import com.payassist.refund.internal.RefundPolicy.MethodRule;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RefundPolicyTest {

    @Test
    void policyBindsFromPropertiesIncludingEnumKeyedMethods() {
        new ApplicationContextRunner()
                .withUserConfiguration(Bind.class)
                .withPropertyValues(shippedPolicy())
                .run(ctx -> {
                    RefundPolicy policy = ctx.getBean(RefundPolicy.class);
                    assertThat(policy.version()).isEqualTo("SYNTHETIC-REFUND-POLICY-2026.11-v1");
                    assertThat(policy.rule(PaymentMethod.BANK_TRANSFER).partialRefundsAllowed()).isFalse();
                    assertThat(policy.rule(PaymentMethod.CARD).refundWindowDays()).isEqualTo(120);
                    assertThat(policy.supervisorLimit("GBP")).hasToString("1000.00");
                    assertThat(policy.supervisorLimit("INR")).hasToString("100000.00");
                });
    }

    @Test
    void incompletePolicyFailsStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(Bind.class)
                .withPropertyValues("payassist.refund.policy.version=v1",
                        "payassist.refund.policy.supported-currencies=GBP",
                        "payassist.refund.policy.methods.CARD.refund-window-days=120")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> policy("bad version!", Map.of("GBP", new BigDecimal("10"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy("v1", Map.of("GBP", new BigDecimal("10.001"))))
                .hasMessageContaining("decimal places");
        assertThatThrownBy(() -> policy("v1", Map.of("GBP", new BigDecimal("-1"))))
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new MethodRule(true, 0, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsAreNormalisedToCurrencyScale() {
        assertThat(policy("v1", Map.of("GBP", new BigDecimal("10"))).supervisorLimit("GBP")).hasToString("10.00");
    }

    private static RefundPolicy policy(String version, Map<String, BigDecimal> limits) {
        Map<PaymentMethod, MethodRule> methods = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethod m : PaymentMethod.values()) {
            methods.put(m, new MethodRule(true, 30, true));
        }
        return new RefundPolicy(version, Set.of("GBP"), methods, limits);
    }

    /** Mirrors application.yml; the real file is exercised end to end by RefundEligibilityApiIT. */
    private static String[] shippedPolicy() {
        String p = "payassist.refund.policy.";
        return new String[] {
            p + "version=SYNTHETIC-REFUND-POLICY-2026.11-v1",
            p + "supported-currencies=GBP,USD,EUR,INR",
            p + "methods.CARD.refunds-enabled=true", p + "methods.CARD.refund-window-days=120",
            p + "methods.CARD.partial-refunds-allowed=true",
            p + "methods.WALLET.refunds-enabled=true", p + "methods.WALLET.refund-window-days=90",
            p + "methods.WALLET.partial-refunds-allowed=true",
            p + "methods.BANK_TRANSFER.refunds-enabled=true", p + "methods.BANK_TRANSFER.refund-window-days=60",
            p + "methods.BANK_TRANSFER.partial-refunds-allowed=false",
            p + "supervisor-approval-limits.GBP=1000.00", p + "supervisor-approval-limits.USD=1250.00",
            p + "supervisor-approval-limits.EUR=1150.00", p + "supervisor-approval-limits.INR=100000.00"
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RefundPolicy.class)
    static class Bind {
    }
}
