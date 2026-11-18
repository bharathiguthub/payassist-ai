package com.payassist.audit.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The checkpoint ledger file on its own: format, MAC chain and every kind of file tampering. */
class AuditCheckpointLedgerTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(UTF_8);
    private static final String H1 = "1".repeat(64);
    private static final String H2 = "2".repeat(64);
    private static final String G = "a".repeat(64);

    @TempDir
    Path dir;

    private Path file;

    @BeforeEach
    void threeCheckpoints() throws Exception {
        file = dir.resolve("audit-checkpoints.jsonl");
        var c1 = AuditCheckpointLedger.next(null, Instant.parse("2026-11-17T10:00:00Z"), 5, H1, 5, 164, G, KEY);
        var c2 = AuditCheckpointLedger.next(c1, Instant.parse("2026-11-17T11:00:00Z"), 9, H2, 9, 164, G, KEY);
        var c3 = AuditCheckpointLedger.next(c2, Instant.parse("2026-11-17T12:00:00Z"), 9, H2, 9, 164, G, KEY);
        for (var c : List.of(c1, c2, c3)) {
            AuditCheckpointLedger.append(file, c);
        }
    }

    @Test
    void anIntactLedgerVerifiesAndLinksEveryLineToThePreviousMac() {
        var result = AuditCheckpointLedger.read(file, KEY);
        assertThat(result.ok()).isTrue();
        assertThat(result.checkpoints()).extracting(AuditCheckpointLedger.Checkpoint::seq).containsExactly(1L, 2L, 3L);
        assertThat(result.checkpoints().get(0).prevMac()).isEqualTo(AuditCheckpointLedger.ZERO_MAC);
        assertThat(result.checkpoints().get(1).prevMac()).isEqualTo(result.checkpoints().get(0).mac());
    }

    @Test
    void aMissingFileIsAnEmptyLedger() {
        var result = AuditCheckpointLedger.read(dir.resolve("none.jsonl"), KEY);
        assertThat(result.ok()).isTrue();
        assertThat(result.checkpoints()).isEmpty();
    }

    @Test
    void editingAnAttestedHeadBreaksItsMac() throws Exception {
        rewrite(lines -> lines.set(1, lines.get(1).replace("\"headChainSeq\":9", "\"headChainSeq\":8")));
        assertFailure("LEDGER_MAC_MISMATCH", 2);
    }

    @Test
    void removingAMiddleLineBreaksTheSequence() throws Exception {
        rewrite(lines -> lines.remove(1));
        assertFailure("LEDGER_SEQUENCE_BROKEN", 2);
    }

    @Test
    void reorderingLinesIsDetected() throws Exception {
        rewrite(lines -> {
            String second = lines.remove(1);
            lines.add(2, second);
        });
        assertFailure("LEDGER_SEQUENCE_BROKEN", 2);
    }

    @Test
    void aLineForgedWithoutTheKeyIsDetected() throws Exception {
        var forged = AuditCheckpointLedger.next(AuditCheckpointLedger.read(file, KEY).last(), Instant.now(), 12,
                H1, 12, 164, G, "another-key-another-key-another-key".getBytes(UTF_8));
        AuditCheckpointLedger.append(file, forged);
        assertFailure("LEDGER_MAC_MISMATCH", 4);
    }

    @Test
    void theWrongKeyCannotVerifyTheLedger() {
        var result = AuditCheckpointLedger.read(file, "x".repeat(32).getBytes(UTF_8));
        assertThat(result.failureReason()).isEqualTo("LEDGER_MAC_MISMATCH");
        assertThat(result.failureLine()).isEqualTo(1);
    }

    @Test
    void malformedOrExtraContentIsDetected() throws Exception {
        rewrite(lines -> lines.add("{\"note\":\"hello\"}"));
        assertFailure("LEDGER_MALFORMED_LINE", 4);
    }

    @Test
    void keysMustBeLongAndBase64url() {
        assertThat(AuditCheckpointLedger.decodeKey(Base64.getUrlEncoder().withoutPadding().encodeToString(KEY)))
                .isEqualTo(KEY);
        assertThatThrownBy(() -> AuditCheckpointLedger.decodeKey("c2hvcnQ")).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> AuditCheckpointLedger.decodeKey(null)).hasMessageContaining("not set");
        assertThatThrownBy(() -> AuditCheckpointLedger.decodeKey("***")).hasMessageContaining("base64url");
    }

    private void assertFailure(String reason, int line) {
        var result = AuditCheckpointLedger.read(file, KEY);
        assertThat(result.failureReason()).isEqualTo(reason);
        assertThat(result.failureLine()).isEqualTo(line);
    }

    private void rewrite(java.util.function.Consumer<List<String>> change) throws Exception {
        List<String> lines = new ArrayList<>(Files.readAllLines(file, UTF_8));
        change.accept(lines);
        Files.write(file, lines, UTF_8);
    }
}
