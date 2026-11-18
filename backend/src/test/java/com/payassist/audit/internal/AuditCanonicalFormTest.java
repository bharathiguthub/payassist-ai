package com.payassist.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Java side of canonical encoding v1; {@code AuditChainIT} checks it matches the SQL functions byte for byte. */
class AuditCanonicalFormTest {

    @Test
    void fieldsAreLengthPrefixedWithUtf8ByteLength() {
        assertThat(AuditCanonicalForm.field("abc")).isEqualTo("3:abc;");
        assertThat(AuditCanonicalForm.field("")).isEqualTo("0:;");
        assertThat(AuditCanonicalForm.field(null)).isEqualTo("-;");
        assertThat(AuditCanonicalForm.field("é")).isEqualTo("2:é;"); // two UTF-8 bytes
    }

    @Test
    void separatorsInsideValuesCannotShiftFields() {
        // Without length prefixes these two rows would encode identically.
        assertThat(AuditCanonicalForm.field("a;") + AuditCanonicalForm.field("b"))
                .isNotEqualTo(AuditCanonicalForm.field("a") + AuditCanonicalForm.field(";b"));
    }

    @Test
    void timestampsAreUtcWithMicroseconds() {
        assertThat(AuditCanonicalForm.timestamp(Instant.parse("2026-11-16T12:00:00Z")))
                .isEqualTo("2026-11-16T12:00:00.000000Z");
        assertThat(AuditCanonicalForm.timestamp(Instant.parse("2026-11-16T12:00:00.123456Z")))
                .isEqualTo("2026-11-16T12:00:00.123456Z");
    }

    @Test
    void detailsAreSortedByKeyBytesAndNullDiffersFromEmpty() {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("status", "OPEN");
        details.put("code", "X");
        details.put("Z", "upper sorts first");
        assertThat(AuditCanonicalForm.details(details))
                .isEqualTo("1:Z;17:upper sorts first;4:code;1:X;6:status;4:OPEN;");
        assertThat(AuditCanonicalForm.details(Map.of())).isEmpty();
        assertThat(AuditCanonicalForm.details(null)).isNull();
    }

    @Test
    void chainedRowEncodingCoversEveryColumnInOrder() {
        var row = new AuditCanonicalForm.Row(7, 3L, "a".repeat(64), "ignored", Instant.parse("2026-11-16T12:00:00Z"),
                "agent.alice", "sub-1", "USER", null, "VIEW", "PAYMENT", "PAY-000042", "cid-12345678", "SUCCESS",
                null);
        assertThat(AuditCanonicalForm.chained(row)).isEqualTo("v1;1:7;1:3;64:" + "a".repeat(64) + ";"
                + "27:2026-11-16T12:00:00.000000Z;11:agent.alice;5:sub-1;4:USER;-;4:VIEW;7:PAYMENT;10:PAY-000042;"
                + "12:cid-12345678;7:SUCCESS;-;");
    }

    @Test
    void sha256IsHexEncoded() {
        assertThat(AuditCanonicalForm.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
