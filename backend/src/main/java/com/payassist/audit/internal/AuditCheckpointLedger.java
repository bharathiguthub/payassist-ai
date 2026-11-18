package com.payassist.audit.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * External, append-only ledger of audit-chain checkpoints (ADR 0013 section 10). One JSON object per line; each line
 * carries the chain head it attests and an HMAC-SHA256 over its canonical form <b>including the previous line's MAC</b>,
 * so the ledger is itself a chain:
 *
 * <pre>mac(n) = HMAC(key, "pac1|n|createdAt|headChainSeq|headRowHash|chainedRows|legacyRows|genesisRowHash|mac(n-1)")</pre>
 *
 * Editing, reordering or removing a line (other than the last ones) breaks a MAC or a link; forging a line needs the key,
 * which only the checkpoint job has (never the backend or the database). Removing the newest lines is detectable only
 * against a copy of the latest MAC kept elsewhere (documented limitation).
 */
final class AuditCheckpointLedger {

    static final String ZERO_MAC = "0".repeat(64);
    static final int MIN_KEY_BYTES = 32;
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    private static final ObjectMapper JSON = new ObjectMapper();

    private AuditCheckpointLedger() {
    }

    record Checkpoint(long seq, Instant createdAt, long headChainSeq, String headRowHash, long chainedRows,
            long legacyRows, String genesisRowHash, String prevMac, String mac) {

        String canonical() {
            return canonical(seq, createdAt, headChainSeq, headRowHash, chainedRows, legacyRows, genesisRowHash,
                    prevMac);
        }

        static String canonical(long seq, Instant createdAt, long headChainSeq, String headRowHash, long chainedRows,
                long legacyRows, String genesisRowHash, String prevMac) {
            return "pac1|" + seq + "|" + createdAt + "|" + headChainSeq + "|" + headRowHash + "|" + chainedRows + "|"
                    + legacyRows + "|" + genesisRowHash + "|" + prevMac;
        }
    }

    /** Result of reading the ledger: all checkpoints, or the first problem (1-based line number). */
    record ReadResult(List<Checkpoint> checkpoints, String failureReason, Integer failureLine) {
        boolean ok() {
            return failureReason == null;
        }

        Checkpoint last() {
            return checkpoints.isEmpty() ? null : checkpoints.get(checkpoints.size() - 1);
        }
    }

    /** Reads and fully verifies the ledger. A missing file is an empty ledger. */
    static ReadResult read(Path file, byte[] key) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, UTF_8);
        } catch (NoSuchFileException e) {
            return new ReadResult(List.of(), null, null);
        } catch (IOException e) {
            return new ReadResult(List.of(), "LEDGER_UNREADABLE", null);
        }
        List<Checkpoint> checkpoints = new ArrayList<>();
        String expectedPrev = ZERO_MAC;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                return new ReadResult(checkpoints, "LEDGER_MALFORMED_LINE", i + 1);
            }
            Checkpoint c;
            try {
                c = parse(line);
            } catch (RuntimeException | IOException e) {
                return new ReadResult(checkpoints, "LEDGER_MALFORMED_LINE", i + 1);
            }
            if (c.seq() != i + 1) {
                return new ReadResult(checkpoints, "LEDGER_SEQUENCE_BROKEN", i + 1);
            }
            if (!c.prevMac().equals(expectedPrev)) {
                return new ReadResult(checkpoints, "LEDGER_LINK_BROKEN", i + 1);
            }
            if (!MessageDigest.isEqual(hmac(key, c.canonical()).getBytes(UTF_8), c.mac().getBytes(UTF_8))) {
                return new ReadResult(checkpoints, "LEDGER_MAC_MISMATCH", i + 1);
            }
            if (!checkpoints.isEmpty() && c.headChainSeq() < checkpoints.get(checkpoints.size() - 1).headChainSeq()) {
                return new ReadResult(checkpoints, "LEDGER_HEAD_WENT_BACKWARDS", i + 1);
            }
            checkpoints.add(c);
            expectedPrev = c.mac();
        }
        return new ReadResult(List.copyOf(checkpoints), null, null);
    }

    /** The next checkpoint after {@code previous} (or the first one), signed with {@code key}. */
    static Checkpoint next(Checkpoint previous, Instant now, long headChainSeq, String headRowHash, long chainedRows,
            long legacyRows, String genesisRowHash, byte[] key) {
        long seq = previous == null ? 1 : previous.seq() + 1;
        String prevMac = previous == null ? ZERO_MAC : previous.mac();
        String mac = hmac(key, Checkpoint.canonical(seq, now, headChainSeq, headRowHash, chainedRows, legacyRows,
                genesisRowHash, prevMac));
        return new Checkpoint(seq, now, headChainSeq, headRowHash, chainedRows, legacyRows, genesisRowHash, prevMac,
                mac);
    }

    /**
     * Appends one line under an exclusive file lock and forces it to disk. Never rewrites existing content (the file is
     * opened in APPEND mode). Callers must have verified the ledger first.
     */
    static void append(Path file, Checkpoint c) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("seq", c.seq());
        json.put("createdAt", c.createdAt().toString());
        json.put("headChainSeq", c.headChainSeq());
        json.put("headRowHash", c.headRowHash());
        json.put("chainedRows", c.chainedRows());
        json.put("legacyRows", c.legacyRows());
        json.put("genesisRowHash", c.genesisRowHash());
        json.put("prevMac", c.prevMac());
        json.put("mac", c.mac());
        byte[] line = (JSON.writeValueAsString(json) + "\n").getBytes(UTF_8);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND); FileLock ignored = channel.lock()) {
            channel.write(ByteBuffer.wrap(line));
            channel.force(true);
        }
    }

    static byte[] decodeKey(String base64url) {
        if (base64url == null || base64url.isBlank()) {
            throw new IllegalStateException("PAYASSIST_AUDIT_CHECKPOINT_KEY is not set");
        }
        byte[] key;
        try {
            key = java.util.Base64.getUrlDecoder().decode(base64url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PAYASSIST_AUDIT_CHECKPOINT_KEY is not valid base64url");
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("PAYASSIST_AUDIT_CHECKPOINT_KEY must be at least 32 bytes");
        }
        return key;
    }

    static String hmac(byte[] key, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static Checkpoint parse(String line) throws IOException {
        var node = JSON.readTree(line);
        if (!node.isObject() || node.size() != 9) {
            throw new IllegalArgumentException("unexpected fields");
        }
        var c = new Checkpoint(node.get("seq").longValue(), Instant.parse(node.get("createdAt").textValue()),
                node.get("headChainSeq").longValue(), hex(node.get("headRowHash").textValue()),
                node.get("chainedRows").longValue(), node.get("legacyRows").longValue(),
                hex(node.get("genesisRowHash").textValue()), hex(node.get("prevMac").textValue()),
                hex(node.get("mac").textValue()));
        if (c.seq() < 1 || c.headChainSeq() < 1 || c.chainedRows() < 1 || c.legacyRows() < 0) {
            throw new IllegalArgumentException("out of range");
        }
        return c;
    }

    private static String hex(String value) {
        if (value == null || !HEX64.matcher(value).matches()) {
            throw new IllegalArgumentException("not a 64-char lowercase hex value");
        }
        return value;
    }
}
