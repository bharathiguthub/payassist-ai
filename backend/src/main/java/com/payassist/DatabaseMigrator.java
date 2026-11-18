package com.payassist;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import org.flywaydb.core.Flyway;

/**
 * One-off migration job (ADR 0013, milestone 6.3): applies Flyway migrations with the <b>schema owner</b>'s
 * credentials and exits, so the long-running application never holds them. Runs from the backend image:
 *
 * <pre>java -cp /app/app.jar -Dloader.main=com.payassist.DatabaseMigrator \
 *     org.springframework.boot.loader.launch.PropertiesLauncher</pre>
 *
 * Environment:
 * <ul>
 *   <li>{@code PAYASSIST_DB_URL}, {@code PAYASSIST_MIGRATION_USERNAME}, {@code PAYASSIST_MIGRATION_PASSWORD}
 *       (schema owner; required)</li>
 *   <li>{@code PAYASSIST_MIGRATION_LOCATIONS}: comma-separated Flyway locations (default {@code classpath:db/migration};
 *       the dev stack adds {@code classpath:db/seed})</li>
 *   <li>{@code PAYASSIST_DB_AUDITOR_PASSWORD}: optional; the same for the read-only role {@code payassist_auditor}
 *       (V9) used by the external audit checkpoint job</li>
 *   <li>{@code PAYASSIST_DB_APP_PASSWORD}: optional; when set, the runtime role {@code payassist_app} (created
 *       NOLOGIN by V8) is given LOGIN with this password, keeping it in sync with {@code .env}</li>
 * </ul>
 * Secrets are never printed.
 */
public final class DatabaseMigrator {

    static final String RUNTIME_ROLE = "payassist_app";
    static final String AUDITOR_ROLE = "payassist_auditor";

    private DatabaseMigrator() {
    }

    public static void main(String[] args) throws SQLException {
        String url = required("PAYASSIST_DB_URL");
        String user = required("PAYASSIST_MIGRATION_USERNAME");
        String password = required("PAYASSIST_MIGRATION_PASSWORD");
        String[] locations = Arrays.stream(System.getenv()
                        .getOrDefault("PAYASSIST_MIGRATION_LOCATIONS", "classpath:db/migration").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);

        var result = Flyway.configure().dataSource(url, user, password).locations(locations).load().migrate();
        System.out.printf("Flyway: %d migration(s) applied, schema version %s%n", result.migrationsExecuted,
                result.targetSchemaVersion);

        enableLogin(url, user, password, RUNTIME_ROLE, System.getenv("PAYASSIST_DB_APP_PASSWORD"));
        enableLogin(url, user, password, AUDITOR_ROLE, System.getenv("PAYASSIST_DB_AUDITOR_PASSWORD"));
    }

    /** Gives a role created NOLOGIN by a migration its login, if a password is configured; otherwise leaves it as is. */
    private static void enableLogin(String url, String user, String password, String role, String rolePassword)
            throws SQLException {
        if (rolePassword == null || rolePassword.isBlank()) {
            return;
        }
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            // ALTER ROLE cannot take bind parameters; the value is quoted as a SQL literal.
            s.execute("ALTER ROLE " + role + " WITH LOGIN PASSWORD " + literal(rolePassword));
        }
        System.out.println("Role " + role + ": login enabled (password from environment)");
    }

    /** Standard SQL string literal (standard_conforming_strings is on by default since PostgreSQL 9.1). */
    static String literal(String value) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("password must not contain NUL");
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return value;
    }
}
