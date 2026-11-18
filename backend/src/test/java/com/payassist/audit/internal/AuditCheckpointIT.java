package com.payassist.audit.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payassist.support.PostgresTestcontainersConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * External checkpoints (ADR 0013 section 10) against real PostgreSQL. The tool runs as the read-only
 * {@code payassist_auditor}; tampering is done as the container superuser, i.e. with owner rights that can defeat every
 * in-database control. Each test gets a fresh database and ledger file.
 */
@Testcontainers
class AuditCheckpointIT {

    private static final byte[] KEY = "test-only-checkpoint-key-32bytes!".getBytes(UTF_8);
    private static final String AUDITOR_PASSWORD = "test-only-auditor-password";

    @Container
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestcontainersConfig.IMAGE);

    @TempDir
    Path dir;

    private JdbcTemplate owner;
    private DataSource auditor;
    private Path ledger;

    @BeforeEach
    void migrateAndAudit() {
        var ownerDs = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ownerDs).locations("classpath:db/migration").load().migrate();
        owner = new JdbcTemplate(ownerDs);
        owner.execute("alter role payassist_auditor login password '" + AUDITOR_PASSWORD + "'");
        auditor = new DriverManagerDataSource(postgres.getJdbcUrl(), "payassist_auditor", AUDITOR_PASSWORD);
        ledger = dir.resolve("audit-checkpoints.jsonl");
        audit(5);
    }

    @Test
    void createAppendsSignedCheckpointsAndNeverRewritesOldOnes() throws Exception {
        var first = run(true);
        assertThat(first.ok()).isTrue();
        assertThat(first.appended()).isTrue();
        assertThat(first.headChainSeq()).isEqualTo(6); // genesis + 5
        String firstLine = Files.readAllLines(ledger, UTF_8).get(0);

        audit(3);
        var second = run(true);
        assertThat(second.ok()).isTrue();
        assertThat(second.headChainSeq()).isEqualTo(9);
        assertThat(second.checkpoints()).isEqualTo(2);
        assertThat(Files.readAllLines(ledger, UTF_8)).hasSize(2).first().isEqualTo(firstLine);

        var verify = run(false);
        assertThat(verify.ok()).isTrue();
        assertThat(verify.appended()).isFalse();
        assertThat(Files.readAllLines(ledger, UTF_8)).hasSize(2);

        // Every run is reported in audit_checkpoint_runs (V10) for the admin status panel, without key or MAC.
        assertThat(owner.queryForList("select mode || '/' || result || '/' || appended || '/' || latest_checkpoint_head "
                + "from audit_checkpoint_runs order by id", String.class))
                .containsExactly("CREATE/OK/true/6", "CREATE/OK/true/9", "VERIFY/OK/false/9");
    }

    @Test
    void deletingTheNewestRowsPassesTheDatabaseCheckButNotTheCheckpoint() {
        assertThat(run(true).ok()).isTrue(); // checkpoint at #6
        withTriggerDisabled("trg_audit_events_no_update_delete",
                () -> owner.update("delete from audit_events where chain_seq >= 5"));

        // The in-database verification cannot see a truncated tail: what is left is a valid chain.
        assertThat(databaseCheck().result()).isEqualTo("VALID");
        var report = run(false);
        assertThat(report.ok()).isFalse();
        assertThat(report.reason()).isEqualTo("CHECKPOINTED_ROW_MISSING");
    }

    @Test
    void aWholeChainRecomputedWithOwnerRightsPassesTheDatabaseCheckButNotTheCheckpoint() {
        assertThat(run(true).ok()).isTrue();
        withTriggerDisabled("trg_audit_events_no_update_delete", () -> {
            owner.update("update audit_events set resource_id = 'PAY-999999' where chain_seq = 3");
            owner.execute("""
                    DO $$
                    DECLARE r RECORD; prev VARCHAR(64);
                    BEGIN
                        SELECT row_hash INTO prev FROM audit_events WHERE chain_seq = 2;
                        FOR r IN SELECT id FROM audit_events WHERE chain_seq >= 3 ORDER BY chain_seq LOOP
                            UPDATE audit_events SET prev_hash = prev WHERE id = r.id;
                            UPDATE audit_events a
                               SET row_hash = encode(sha256(convert_to(audit_row_canonical(a), 'UTF8')), 'hex')
                             WHERE id = r.id RETURNING row_hash INTO prev;
                        END LOOP;
                    END $$""");
        });

        assertThat(databaseCheck().result()).as("forged chain is internally consistent").isEqualTo("VALID");
        var report = run(false);
        assertThat(report.reason()).isEqualTo("CHECKPOINT_MISMATCH");
    }

    @Test
    void aTamperedLedgerBlocksNewCheckpointsAndIsLeftAsEvidence() throws Exception {
        run(true);
        audit(1);
        run(true);
        List<String> lines = Files.readAllLines(ledger, UTF_8);
        Files.write(ledger, List.of(lines.get(0), lines.get(1).replace("\"chainedRows\":7", "\"chainedRows\":70")),
                UTF_8);
        byte[] tampered = Files.readAllBytes(ledger);

        var report = run(true);
        assertThat(report.reason()).isEqualTo("LEDGER_MAC_MISMATCH");
        assertThat(report.appended()).isFalse();
        assertThat(Files.readAllBytes(ledger)).isEqualTo(tampered);
    }

    @Test
    void aBrokenDatabaseChainBlocksNewCheckpoints() {
        run(true);
        withTriggerDisabled("trg_audit_events_no_update_delete",
                () -> owner.update("update audit_events set actor = 'someone.else' where chain_seq = 2"));
        var report = run(true);
        assertThat(report.reason()).isEqualTo("DB_CHAIN_ROW_HASH_MISMATCH");
        assertThat(report.checkpoints()).isEqualTo(1);
        assertThat(owner.queryForObject("select result || '/' || reason from audit_checkpoint_runs order by id desc limit 1",
                String.class)).isEqualTo("FAILED/DB_CHAIN_ROW_HASH_MISMATCH");
    }

    @Test
    void theLedgerCannotBeVerifiedWithAnotherKey() {
        run(true);
        var report = AuditCheckpointTool.run(false, auditor, ledger, "another-test-key-of-32-bytes-long".getBytes(UTF_8),
                Clock.systemUTC());
        assertThat(report.reason()).isEqualTo("LEDGER_MAC_MISMATCH");
    }

    @Test
    void theAuditorRoleCanOnlyReadTheAuditTrail() {
        var jdbc = new JdbcTemplate(auditor);
        assertThat(jdbc.queryForObject("select count(*) from audit_events", Long.class)).isEqualTo(6);
        for (String sql : List.of(
                "insert into audit_events (occurred_at, actor, action, resource_type, resource_id) "
                        + "values (now(), 'x', 'VIEW', 'PAYMENT', 'PAY-000001')",
                "update audit_events set actor = 'x'", "delete from audit_events", "truncate audit_events",
                "update audit_checkpoint_runs set result = 'OK'", "delete from audit_checkpoint_runs",
                "select * from payments", "select * from customers", "create temp table t (id int)",
                "create table t (id int)", "alter table audit_events disable trigger all")) {
            assertThatThrownBy(() -> jdbc.execute(sql)).as(sql)
                    .satisfies(e -> assertThat(((org.springframework.dao.DataAccessException) e)
                            .getMostSpecificCause().getMessage())
                            .containsAnyOf("permission denied", "must be owner"));
        }
    }

    private AuditCheckpointTool.Report run(boolean create) {
        return AuditCheckpointTool.run(create, auditor, ledger, KEY, Clock.systemUTC());
    }

    private AuditIntegrityReport databaseCheck() {
        var verifier = new AuditIntegrityVerifier(auditor, new ObjectMapper(), Clock.systemUTC());
        return new TransactionTemplate(new DataSourceTransactionManager(auditor)).execute(s -> verifier.verify());
    }

    private void audit(int rows) {
        for (int i = 0; i < rows; i++) {
            owner.update("insert into audit_events (occurred_at, actor, actor_subject, action, resource_type, "
                    + "resource_id, outcome) values (now(), 'agent.alice', 'sub-alice', 'VIEW', 'PAYMENT', ?, 'SUCCESS')",
                    "PAY-%06d".formatted(i + 1));
        }
    }

    private void withTriggerDisabled(String trigger, Runnable tampering) {
        owner.execute("alter table audit_events disable trigger " + trigger);
        try {
            tampering.run();
        } finally {
            owner.execute("alter table audit_events enable trigger " + trigger);
        }
    }
}
