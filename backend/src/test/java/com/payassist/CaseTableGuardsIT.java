package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payassist.dispute.DisputeStatus;
import com.payassist.dispute.internal.DisputeTransitions;
import com.payassist.refund.RefundRequestStatus;
import com.payassist.refund.internal.RefundRequestTransitions;
import com.payassist.support.PostgresTestcontainersConfig;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Database-level rules for refund requests and dispute cases (V9, ADR 0013 section 8), enforced for every role
 * including the schema owner, who is used here: immutable columns, final decisions, the state machines and no deletes.
 * The SQL state machines must equal the Java ones ({@link RefundRequestTransitions}, {@link DisputeTransitions}).
 * The runtime role's column-level privileges are covered by {@code RuntimeRolePrivilegesIT}.
 */
@Testcontainers
class CaseTableGuardsIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestcontainersConfig.IMAGE);

    private static JdbcTemplate owner;

    @BeforeAll
    static void migrateWithSeed() {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration", "classpath:db/seed").load().migrate();
        owner = new JdbcTemplate(ds);
    }

    @Test
    void sqlRefundStateMachineEqualsJava() {
        for (RefundRequestStatus from : RefundRequestStatus.values()) {
            for (RefundRequestStatus to : RefundRequestStatus.values()) {
                assertThat(owner.queryForObject("select refund_request_transition_allowed(?, ?)", Boolean.class,
                        from.name(), to.name())).as("%s -> %s", from, to)
                        .isEqualTo(RefundRequestTransitions.isAllowed(from, to));
            }
        }
    }

    @Test
    void sqlDisputeStateMachineEqualsJava() {
        for (DisputeStatus from : DisputeStatus.values()) {
            for (DisputeStatus to : DisputeStatus.values()) {
                boolean java = Arrays.stream(DisputeTransitions.Transition.values())
                        .anyMatch(t -> t.to == to && t.allowedFrom(from));
                assertThat(owner.queryForObject("select dispute_case_transition_allowed(?, ?)", Boolean.class,
                        from.name(), to.name())).as("%s -> %s", from, to).isEqualTo(java);
            }
        }
    }

    @Test
    void disputeCaseRulesHoldEvenForTheOwner() {
        String ref = openCase(0);
        refused("update dispute_cases set disputed_amount = disputed_amount + 1 where case_ref = ?", ref, "immutable");
        refused("update dispute_cases set payment_ref = 'PAY-000001' where case_ref = ?", ref, "immutable");
        refused("update dispute_cases set opened_by_username = 'someone' where case_ref = ?", ref, "immutable");
        refused("update dispute_cases set status = 'WON' where case_ref = ?", ref, "not allowed");
        refused("update dispute_cases set review_requested_by_subject = 'sub-x' where case_ref = ?", ref,
                "review requester");
        refused("update dispute_cases set version = version - 1 where case_ref = ?", ref, "backwards");
        refused("delete from dispute_cases where case_ref = ?", ref, "cannot be deleted");

        // A valid transition with the review requester set at the right moment is accepted.
        ok("update dispute_cases set status = 'UNDER_INVESTIGATION', version = version + 1 where case_ref = ?", ref);
        ok("update dispute_cases set status = 'PENDING_REVIEW', review_requested_by_subject = 'sub-alice', "
                + "review_requested_by_username = 'agent.alice', review_requested_at = now(), version = version + 1 "
                + "where case_ref = ?", ref);
        ok("update dispute_cases set updated_at = now(), version = version + 1 where case_ref = ?", ref); // touch
    }

    @Test
    void closedDisputeCasesAreFinal() {
        String ref = openCase(1);
        ok("update dispute_cases set status = 'WITHDRAWN', version = version + 1 where case_ref = ?", ref);
        refused("update dispute_cases set status = 'OPEN' where case_ref = ?", ref, "final");
        refused("update dispute_cases set updated_at = now() where case_ref = ?", ref, "final");
        assertThatThrownBy(() -> owner.execute("truncate dispute_cases cascade")).hasMessageMatching("(?s).*(cannot be deleted|append-only).*");
    }

    @Test
    void refundRequestRulesHoldEvenForTheOwner() {
        String ref = refundRequest();
        refused("update refund_requests set amount = amount + 1 where refund_ref = ?", ref, "immutable");
        refused("update refund_requests set requested_by_subject = 'sub-other' where refund_ref = ?", ref, "immutable");
        refused("update refund_requests set policy_version = 'X' where refund_ref = ?", ref, "immutable");
        refused("update refund_requests set status = 'ESCALATION_REQUIRED' where refund_ref = ?", ref, "not allowed");
        refused("delete from refund_requests where refund_ref = ?", ref, "cannot be deleted");

        ok("update refund_requests set status = 'APPROVED', version = version + 1 where refund_ref = ?", ref);
        refused("update refund_requests set status = 'REJECTED' where refund_ref = ?", ref, "final");
        assertThatThrownBy(() -> owner.execute("truncate refund_requests cascade"))
                .hasMessageMatching("(?s).*(cannot be deleted|append-only).*");
    }

    private static String openCase(int offset) {
        return owner.queryForObject("select case_ref from dispute_cases where status = 'OPEN' order by case_ref "
                + "offset ? limit 1", String.class, offset);
    }

    private static String refundRequest() {
        String ref = "RFD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        owner.update("""
                insert into refund_requests (id, refund_ref, payment_id, payment_ref, amount, currency, reason_code,
                    status, eligibility_decision, eligibility_reasons, policy_version, requested_by_subject,
                    requested_by_username, requested_at, idempotency_key, request_fingerprint, version, updated_at)
                select gen_random_uuid(), ?, id, payment_ref, 1.00, currency, 'CUSTOMER_REQUEST', 'PENDING_APPROVAL',
                    'ELIGIBLE', 'WITHIN_POLICY', 'TEST', 'sub-alice', 'agent.alice', now(), ?, repeat('0', 64), 0, now()
                from payments where status = 'SETTLED' order by payment_ref limit 1""", ref, ref);
        return ref;
    }

    private static void refused(String sql, String ref, String message) {
        assertThatThrownBy(() -> owner.update(sql, ref)).as(sql).hasMessageContaining(message);
    }

    private static void ok(String sql, String ref) {
        assertThatCode(() -> assertThat(owner.update(sql, ref)).isEqualTo(1)).as(sql).doesNotThrowAnyException();
    }
}
