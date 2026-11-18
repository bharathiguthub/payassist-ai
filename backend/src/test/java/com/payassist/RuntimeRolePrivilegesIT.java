package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payassist.support.IntegrationTest;
import com.payassist.support.PostgresTestcontainersConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The application's own database connection (the runtime role {@code payassist_app}, ADR 0013 / V8) must not be able
 * to change the schema, switch off the audit protections, rewrite history or widen its own privileges. Every
 * statement below is attempted through the application's DataSource and must be refused by PostgreSQL.
 */
@IntegrationTest
class RuntimeRolePrivilegesIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theApplicationConnectsAsTheRuntimeRoleWithoutAnyRoleAttributes() {
        assertThat(jdbc.queryForObject("select current_user", String.class))
                .isEqualTo(PostgresTestcontainersConfig.RUNTIME_ROLE);
        Map<String, Object> role = jdbc.queryForMap("""
                select rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
                from pg_roles where rolname = current_user""");
        assertThat(role).containsOnly(Map.entry("rolsuper", false), Map.entry("rolcreaterole", false),
                Map.entry("rolcreatedb", false), Map.entry("rolreplication", false), Map.entry("rolbypassrls", false));
        // Member of no other role (so SET ROLE cannot reach the owner).
        assertThat(jdbc.queryForObject("""
                select count(*) from pg_auth_members m join pg_roles r on r.oid = m.member
                where r.rolname = current_user""", Long.class)).isZero();
    }

    @Test
    void theRuntimeRoleOwnsNothingAndCannotGrantAnything() {
        assertThat(jdbc.queryForObject("""
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and pg_get_userbyid(c.relowner) = current_user""", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                where n.nspname = 'public' and pg_get_userbyid(p.proowner) = current_user""", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.role_table_grants
                where grantee = current_user and is_grantable = 'YES'""", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.role_table_grants
                where grantee = current_user and privilege_type in ('DELETE', 'TRUNCATE', 'TRIGGER', 'REFERENCES')""",
                Long.class)).isZero();
    }

    @Test
    void grantsAreExactlyWhatTheApplicationNeeds() {
        assertThat(jdbc.queryForList("""
                select table_name || ':' || string_agg(privilege_type, ',' order by privilege_type)
                from information_schema.role_table_grants where grantee = current_user and table_schema = 'public'
                group by table_name order by table_name""", String.class))
                .containsExactly(
                        "audit_events:INSERT,SELECT",
                        "customers:SELECT",
                        "dispute_case_events:INSERT,SELECT",
                        "dispute_cases:INSERT,SELECT",
                        "dispute_evidence:INSERT,SELECT",
                        "dispute_notes:INSERT,SELECT",
                        "payment_events:INSERT,SELECT",
                        "payments:SELECT",
                        "refund_decisions:INSERT,SELECT",
                        "refund_requests:INSERT,SELECT");
        assertThat(jdbc.queryForList("""
                select column_name from information_schema.column_privileges
                where grantee = current_user and table_name = 'payments' and privilege_type = 'UPDATE'
                order by column_name""", String.class))
                .containsExactly("failure_reason", "status", "updated_at", "version");
        // Case files (V9): only the workflow columns, never amounts, payment, requester or idempotency data.
        assertThat(columnUpdates("refund_requests")).containsExactly("status", "updated_at", "version");
        assertThat(columnUpdates("dispute_cases")).containsExactly("review_requested_at", "review_requested_by_subject",
                "review_requested_by_username", "status", "updated_at", "version");
    }

    private List<String> columnUpdates(String table) {
        return jdbc.queryForList("""
                select column_name from information_schema.column_privileges
                where grantee = current_user and table_name = ? and privilege_type = 'UPDATE'
                order by column_name""", String.class, table);
    }

    @Test
    void theRuntimeRoleCannotCreateTemporaryObjects() {
        assertThat(jdbc.queryForObject("select has_database_privilege(current_database(), 'TEMPORARY')",
                Boolean.class)).isFalse();
    }


    /**
     * PostgreSQL answers GRANT without grant option with a warning ("no privileges were granted") rather than an
     * error, so the check is that nothing changed afterwards.
     */
    @Test
    void grantingPrivilegesToItselfOrOthersHasNoEffect() {
        for (String sql : new String[] {"grant delete, truncate on audit_events to payassist_app",
                "grant all on payments to public", "grant update on payment_events to payassist_app"}) {
            try {
                jdbc.execute(sql);
            } catch (DataAccessException refused) {
                // also acceptable
            }
        }
        assertThat(jdbc.queryForObject("select has_table_privilege('audit_events', 'DELETE')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select has_table_privilege('audit_events', 'TRUNCATE')", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("select has_table_privilege('payment_events', 'UPDATE')", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.role_table_grants
                where grantee = 'PUBLIC' and table_schema = 'public'""", Long.class)).isZero();
    }

    @ParameterizedTest(name = "refused: {0}")
    @ValueSource(strings = {
            // DDL
            "create table evil (id int)",
            "create schema evil",
            "create function public.evil() returns int language sql as 'select 1'",
            "alter table audit_events add column evil text",
            "drop table dispute_notes",
            "create index evil on payments (amount)",
            // Switching off or replacing the audit protections
            "alter table audit_events disable trigger trg_audit_events_no_update_delete",
            "alter table audit_events disable trigger all",
            "drop trigger trg_audit_events_chain on audit_events",
            "create or replace function audit_field(v text) returns text language sql as 'select v'",
            "alter function audit_events_chain() owner to payassist_app",
            "set session_replication_role = replica",
            // Rewriting history
            "update audit_events set actor = 'someone.else'",
            "delete from audit_events",
            "truncate audit_events",
            "update payment_events set amount = 0",
            "delete from payment_events",
            "delete from refund_decisions",
            "update dispute_notes set body = 'x'",
            "delete from dispute_cases",
            "delete from payments",
            "update payments set amount = 1",
            "update payments set currency = 'XXX'",
            "insert into payments (id) values (gen_random_uuid())",
            "update customers set full_name = 'x'",
            // Case files: immutable columns and deletes (V9)
            "update refund_requests set amount = 1",
            "update refund_requests set requested_by_subject = 'sub-other'",
            "update refund_requests set policy_version = 'X'",
            "update refund_requests set payment_id = gen_random_uuid()",
            "update dispute_cases set disputed_amount = 1",
            "update dispute_cases set opened_by_subject = 'sub-other'",
            "update dispute_cases set payment_ref = 'PAY-000001'",
            "delete from refund_requests",
            "truncate dispute_cases",
            // Temporary objects (V9)
            "create temp table evil (id int)",
            "create temporary view evil as select 1",
            "set role payassist_auditor",
            "insert into flyway_schema_history (installed_rank) values (999)",
            "select * from flyway_schema_history",
            // Widening its own privileges
            "alter role payassist_app superuser",
            "alter role payassist_app createrole",
            "alter role payassist_app bypassrls",
            "create role evil login",
            "set role test",
    })
    void forbiddenStatementsAreRefused(String sql) {
        assertThatThrownBy(() -> jdbc.execute(sql)).isInstanceOf(DataAccessException.class)
                .extracting(e -> ((DataAccessException) e).getMostSpecificCause().getMessage())
                .satisfies(message -> assertThat((String) message).as("must be a privilege refusal")
                        .containsAnyOf("permission denied", "must be owner", "must be superuser", "must have"));
    }
}
