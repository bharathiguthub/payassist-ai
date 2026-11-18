package com.payassist.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payassist.shared.Actor;
import com.payassist.shared.AuditRecord;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

class AuditDetailPolicyTest {

    private static final Actor ALICE = Actor.user("sub-alice", "agent.alice");

    private static AuditRecord record(String action, Map<String, String> details) {
        return AuditRecord.of(Clock.systemUTC(), ALICE, action, "PAYMENT", "PAY-000042").withDetails(details);
    }

    @Test
    void registeredActionWithAllowedKeysPasses() {
        assertThatCode(() -> AuditDetailPolicy.check(record("REFUND_REQUEST_REJECT",
                Map.of("paymentRef", "PAY-000042", "amount", "10.00", "rejectionReason", "DUPLICATE"))))
                .doesNotThrowAnyException();
        // The error code of a refused operation is allowed on every action.
        assertThatCode(() -> AuditDetailPolicy.check(record("VIEW_TIMELINE", Map.of("code", "NOT_FOUND"))))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownActionIsRefused() {
        assertThatThrownBy(() -> AuditDetailPolicy.check(record("SOMETHING_NEW", Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not registered");
    }

    @Test
    void keyNotAllowedForTheActionIsRefused() {
        assertThatThrownBy(() -> AuditDetailPolicy.check(record("DISPUTE_NOTE_ADD", Map.of("noteText", "hello"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("noteText");
    }

    @Test
    void personalDataInValuesIsRefusedWithoutEchoingIt() {
        assertThatThrownBy(() -> AuditDetailPolicy.check(record("REFUND_REQUEST_REJECT",
                Map.of("rejectionReason", "mail jane@example.com"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("jane");
        assertThatThrownBy(() -> AuditDetailPolicy.check(record("REFUND_REQUEST_REJECT",
                Map.of("rejectionReason", "4111 1111 1111 1111"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("4111");
    }

    @Test
    void opaqueIdentifiersMayContainLongDigitRuns() {
        assertThatCode(() -> AuditDetailPolicy.check(record("AUDIT_LOG_VIEW",
                Map.of("filterCorrelationId", "4111111111111111", "filterActorSubject", "4111-1111-1111-1111"))))
                .doesNotThrowAnyException();
    }

    /**
     * Every audit action constant in the code base ({@code static final String ACTION...}) must be registered, so a
     * new action fails this test instead of failing at runtime (fail closed) in production.
     */
    @Test
    void everyActionConstantInTheCodeBaseIsRegistered() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        var found = new TreeSet<String>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.payassist")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            for (Field field : type.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (Modifier.isStatic(mods) && Modifier.isFinal(mods) && field.getType() == String.class
                        && field.getName().startsWith("ACTION")) {
                    field.setAccessible(true);
                    found.add((String) field.get(null));
                }
            }
        }
        assertThat(found).hasSizeGreaterThan(15);
        assertThat(AuditDetailPolicy.ALLOWED.keySet()).containsAll(found);
    }
}
