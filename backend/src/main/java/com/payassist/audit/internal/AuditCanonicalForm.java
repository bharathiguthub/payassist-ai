package com.payassist.audit.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

/**
 * Java implementation of the canonical audit row encoding (version 1) defined in {@code V7__audit_integrity.sql}.
 * It is deliberately independent of the SQL functions: the verifier does not trust the database to check itself,
 * so a replaced trigger or canonicalization function shows up as a hash mismatch.
 *
 * <p>Every field is length-prefixed with its UTF-8 byte length ({@code 3:abc;}, NULL is {@code -;}), so values
 * cannot be shifted between fields. Detail keys are sorted by their UTF-8 bytes (PostgreSQL {@code COLLATE "C"}).
 */
final class AuditCanonicalForm {

    static final String ZERO_HASH = "0".repeat(64);

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private AuditCanonicalForm() {
    }

    /** A stored audit row, exactly as read from the database (no defaults applied). */
    record Row(long id, Long chainSeq, String prevHash, String rowHash, Instant occurredAt, String actor,
            String actorSubject, String actorType, String onBehalfOfSubject, String action, String resourceType,
            String resourceId, String correlationId, String outcome, Map<String, String> details) {
    }

    static String chained(Row r) {
        return "v1;" + field(Long.toString(r.id())) + field(r.chainSeq() == null ? null : r.chainSeq().toString())
                + field(r.prevHash()) + field(timestamp(r.occurredAt())) + field(r.actor()) + field(r.actorSubject())
                + field(r.actorType()) + field(r.onBehalfOfSubject()) + field(r.action()) + field(r.resourceType())
                + field(r.resourceId()) + field(r.correlationId()) + field(r.outcome()) + field(details(r.details()));
    }

    static String legacy(Row r) {
        return "L1;" + field(Long.toString(r.id())) + field(timestamp(r.occurredAt())) + field(r.actor())
                + field(r.actorSubject()) + field(r.action()) + field(r.resourceType()) + field(r.resourceId())
                + field(r.correlationId()) + field(r.outcome()) + field(details(r.details()));
    }

    static String field(String value) {
        return value == null ? "-;" : value.getBytes(UTF_8).length + ":" + value + ";";
    }

    static String timestamp(Instant instant) {
        return instant == null ? null : TIMESTAMP.format(instant);
    }

    /** NULL details stay NULL; otherwise key/value fields in UTF-8 byte order of the keys. */
    static String details(Map<String, String> details) {
        if (details == null) {
            return null;
        }
        var out = new StringBuilder();
        details.keySet().stream()
                .sorted((a, b) -> Arrays.compareUnsigned(a.getBytes(UTF_8), b.getBytes(UTF_8)))
                .forEach(key -> out.append(field(key)).append(field(details.get(key))));
        return out.toString();
    }

    static String sha256Hex(String canonical) {
        return HexFormat.of().formatHex(sha256().digest(canonical.getBytes(UTF_8)));
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
