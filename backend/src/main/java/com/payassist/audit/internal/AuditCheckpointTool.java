package com.payassist.audit.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * External audit-chain checkpoints (ADR 0013 section 10). Runs as a one-off job, outside the backend, with the read-only
 * {@code payassist_auditor} database role and a ledger file plus HMAC key that the backend never sees:
 *
 * <pre>java -cp /app/app.jar -Dloader.main=com.payassist.audit.internal.AuditCheckpointTool \
 *     org.springframework.boot.loader.launch.PropertiesLauncher verify|create</pre>
 *
 * Both modes verify, in order: (1) the ledger file (sequence, links, MACs); (2) the database chain with
 * {@link AuditIntegrityVerifier}; (3) that every checkpointed head is still in the database with the same hash and that
 * the genesis row is unchanged. That third step detects what the in-database check cannot: removal of the newest
 * rows and a whole chain recomputed by someone with owner rights. {@code create} appends a new checkpoint only if all
 * checks pass (fail closed); it never rewrites the file.
 *
 * <p>Environment: {@code PAYASSIST_DB_URL}, {@code PAYASSIST_AUDITOR_USERNAME} (default payassist_auditor),
 * {@code PAYASSIST_DB_AUDITOR_PASSWORD}, {@code PAYASSIST_AUDIT_CHECKPOINT_KEY} (base64url, at least 32 bytes),
 * {@code PAYASSIST_AUDIT_CHECKPOINT_FILE} (default /checkpoints/audit-checkpoints.jsonl). Exit codes: 0 OK,
 * 2 integrity failure, 1 configuration error. Secrets are never printed.
 */
public final class AuditCheckpointTool {

    static final String OK = "OK";

    private AuditCheckpointTool() {
    }

    /** Outcome of one run. {@code reason} is a stable code when {@code result} is not OK. */
    record Report(String result, String reason, String detail, int checkpoints, Long headChainSeq,
            String headRowHash, boolean appended) {

        boolean ok() {
            return OK.equals(result);
        }
    }

    public static void main(String[] args) {
        String mode = args.length == 0 ? "verify" : args[0];
        Report report;
        try {
            if (!mode.equals("verify") && !mode.equals("create")) {
                throw new IllegalStateException("usage: verify | create");
            }
            byte[] key = AuditCheckpointLedger.decodeKey(System.getenv("PAYASSIST_AUDIT_CHECKPOINT_KEY"));
            String password = System.getenv("PAYASSIST_DB_AUDITOR_PASSWORD");
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("PAYASSIST_DB_AUDITOR_PASSWORD is not set");
            }
            String url = System.getenv("PAYASSIST_DB_URL");
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("PAYASSIST_DB_URL is not set");
            }
            String user = System.getenv().getOrDefault("PAYASSIST_AUDITOR_USERNAME", "payassist_auditor");
            Path file = Path.of(System.getenv().getOrDefault("PAYASSIST_AUDIT_CHECKPOINT_FILE",
                    "/checkpoints/audit-checkpoints.jsonl"));
            report = run(mode.equals("create"), new DriverManagerDataSource(url, user, password), file, key,
                    Clock.systemUTC());
        } catch (IllegalStateException e) {
            System.err.println("Audit checkpoint: configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }
        System.out.printf("Audit checkpoint %s: %s%s | checkpoints=%d | head=#%s %s%s%n", mode, report.result(),
                report.reason() == null ? "" : " " + report.reason(),
                report.checkpoints(), report.headChainSeq(),
                report.headRowHash() == null ? "-" : report.headRowHash().substring(0, 16) + "…",
                report.appended() ? " | appended" : "");
        if (report.detail() != null) {
            System.out.println("  " + report.detail());
        }
        System.exit(report.ok() ? 0 : 2);
    }

    static Report run(boolean create, DataSource dataSource, Path file, byte[] key, Clock clock) {
        if (!create) {
            return check(false, dataSource, file, key, clock);
        }
        // One writer at a time for the whole read-verify-append cycle (a sibling lock file; the ledger stays append-only).
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (FileChannel lock = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = lock.lock()) {
                return check(true, dataSource, file, key, clock);
            }
        } catch (IOException e) {
            return new Report("FAILED", "LEDGER_LOCK_FAILED", e.getClass().getSimpleName(), 0, null, null, false);
        }
    }

    private static Report check(boolean create, DataSource dataSource, Path file, byte[] key, Clock clock) {
        var ledger = AuditCheckpointLedger.read(file, key);
        if (!ledger.ok()) {
            return new Report("FAILED", ledger.failureReason(),
                    ledger.failureLine() == null ? null : "ledger line " + ledger.failureLine(),
                    ledger.checkpoints().size(), null, null, false);
        }

        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); // one consistent snapshot
        var verifier = new AuditIntegrityVerifier(dataSource, new ObjectMapper(), clock);
        var jdbc = new JdbcTemplate(dataSource);

        record Checked(Report report, AuditIntegrityReport chain, String genesisHash) {
        }
        Checked checked = tx.execute(status -> {
            AuditIntegrityReport chain = verifier.verify();
            if (!AuditIntegrityReport.VALID.equals(chain.result())) {
                var f = chain.firstFailure();
                return new Checked(new Report("FAILED", "DB_CHAIN_" + f.reason(), "audit event " + f.auditEventId(),
                        ledger.checkpoints().size(), chain.headChainSeq(), chain.headRowHash(), false), chain, null);
            }
            String genesisHash = rowHash(jdbc, 1);
            for (var c : ledger.checkpoints()) {
                if (!c.genesisRowHash().equals(genesisHash)) {
                    return new Checked(failed("GENESIS_CHANGED", "checkpoint " + c.seq(), ledger.checkpoints(), chain),
                            chain, genesisHash);
                }
                String current = rowHash(jdbc, c.headChainSeq());
                if (current == null) {
                    return new Checked(failed("CHECKPOINTED_ROW_MISSING", "checkpoint " + c.seq() + " attests chain row #"
                            + c.headChainSeq() + ", which is no longer in the database", ledger.checkpoints(), chain), chain,
                            genesisHash);
                }
                if (!current.equals(c.headRowHash())) {
                    return new Checked(failed("CHECKPOINT_MISMATCH", "checkpoint " + c.seq() + ": chain row #" + c.headChainSeq()
                            + " has a different hash than when it was checkpointed", ledger.checkpoints(), chain), chain,
                            genesisHash);
                }
            }
            return new Checked(new Report(OK, null, null, ledger.checkpoints().size(), chain.headChainSeq(),
                    chain.headRowHash(), false), chain, genesisHash);
        });
        Report report = checked.report();

        if (!report.ok() || !create) {
            return report;
        }
        // The checkpoint attests exactly the head that was just verified, from the same snapshot.
        AuditIntegrityReport head = checked.chain();
        var next = AuditCheckpointLedger.next(ledger.last(), Instant.now(clock), head.headChainSeq(),
                head.headRowHash(), head.chainedRows(), head.legacyRows(), checked.genesisHash(), key);
        try {
            AuditCheckpointLedger.append(file, next);
        } catch (IOException e) {
            return new Report("FAILED", "LEDGER_WRITE_FAILED", e.getClass().getSimpleName(),
                    ledger.checkpoints().size(), head.headChainSeq(), head.headRowHash(), false);
        }
        return new Report(OK, null, null, ledger.checkpoints().size() + 1, next.headChainSeq(), next.headRowHash(),
                true);
    }

    private static Report failed(String reason, String detail, List<AuditCheckpointLedger.Checkpoint> checkpoints,
            AuditIntegrityReport chain) {
        return new Report("FAILED", reason, detail, checkpoints.size(), chain.headChainSeq(), chain.headRowHash(),
                false);
    }

    private static String rowHash(JdbcTemplate jdbc, long chainSeq) {
        List<String> hashes = jdbc.queryForList("select row_hash from audit_events where chain_seq = ?", String.class,
                chainSeq);
        return hashes.isEmpty() ? null : hashes.get(0);
    }
}
