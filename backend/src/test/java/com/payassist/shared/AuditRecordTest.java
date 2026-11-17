package com.payassist.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditRecordTest {

    private final AuditRecord base = AuditRecord.of(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            Actor.user("sub", "agent.alice"), "ACTION", "PAYMENT", "PAY-000001");

    @Test
    void keepsDetailsInInsertionOrderAndImmutable() {
        AuditRecord record = base.withDetails(new java.util.LinkedHashMap<>(Map.of("decision", "ELIGIBLE")));
        assertThat(record.details()).containsExactly(Map.entry("decision", "ELIGIBLE"));
        assertThatThrownBy(() -> record.details().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(base.details()).isEmpty();
    }

    @Test
    void rejectsFreeTextShapedDetails() {
        assertThatThrownBy(() -> base.withDetails(Map.of("customer email", "x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.withDetails(Map.of("note", "x".repeat(201)))).isInstanceOf(IllegalArgumentException.class);
        Map<String, String> tooMany = new HashMap<>();
        for (int i = 0; i < 21; i++) {
            tooMany.put("k" + i, "v");
        }
        assertThatThrownBy(() -> base.withDetails(tooMany)).isInstanceOf(IllegalArgumentException.class);
    }
}
