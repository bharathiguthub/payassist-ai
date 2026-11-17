package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;

import com.payassist.support.PostgresTestcontainersConfig;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Upgrading a database that already holds Phase 1 data (schema V1 + the 400 synthetic payments) to
 * the latest schema: existing data must be preserved unchanged and every payment must get a history.
 * Uses Flyway directly (no Spring context) so the database can be stopped at V1 first.
 */
@Testcontainers
class MigrationUpgradeIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestcontainersConfig.IMAGE);

    @Test
    void upgradeFromPhase1PreservesDataAndBackfillsHistory() {
        DataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 1. Phase 1 database: V1 schema plus the Phase 1 seed.
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("1").load().migrate();
        new ResourceDatabasePopulator(new ClassPathResource("db/seed/R__synthetic_seed_data.sql")).execute(dataSource);
        List<Map<String, Object>> before = jdbc.queryForList(
                "select payment_ref, amount, currency, status, failure_reason, updated_at from payments order by payment_ref");
        assertThat(before).hasSize(400);
        jdbc.update("insert into audit_events (occurred_at, actor, action, resource_type, resource_id) "
                + "values (now(), 'agent.alice', 'VIEW', 'PAYMENT', 'PAY-000042')");

        // 2. Upgrade with the same Flyway locations the dev profile uses.
        Flyway latest = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/seed").load();
        latest.migrate();
        latest.validate();

        // Phase 1 data is untouched; the new version column starts at 0.
        assertThat(jdbc.queryForList(
                "select payment_ref, amount, currency, status, failure_reason, updated_at from payments order by payment_ref"))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from customers", Long.class)).isEqualTo(40);
        assertThat(jdbc.queryForObject("select count(*) from payments where version <> 0", Long.class)).isZero();

        // Each payment: one LEGACY_SNAPSHOT at sequence 1 (its status at upgrade time), then the seeded history.
        assertThat(jdbc.queryForObject("""
                select count(*) from payment_events e join payments p on p.id = e.payment_id
                where e.event_type = 'LEGACY_SNAPSHOT' and e.sequence_no = 1 and e.to_status = p.status
                  and e.source = 'MIGRATION' and e.occurred_at = p.updated_at""", Long.class)).isEqualTo(400);
        assertThat(jdbc.queryForObject(
                "select count(distinct payment_id) from payment_events where sequence_no > 1", Long.class))
                .isEqualTo(400);

        // Phase 1 audit rows survive V3; the new context columns are simply empty for them.
        assertThat(jdbc.queryForMap("select actor, action, actor_subject, correlation_id from audit_events"))
                .containsEntry("actor", "agent.alice").containsEntry("action", "VIEW")
                .containsEntry("actor_subject", null).containsEntry("correlation_id", null);
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version in ('2', '3', '4', '5') and success", Long.class))
                .isEqualTo(4);

        // Re-running is a no-op (repeatable seed is idempotent; nothing pending).
        long events = jdbc.queryForObject("select count(*) from payment_events", Long.class);
        latest.migrate();
        assertThat(jdbc.queryForObject("select count(*) from payment_events", Long.class)).isEqualTo(events);
    }
}
