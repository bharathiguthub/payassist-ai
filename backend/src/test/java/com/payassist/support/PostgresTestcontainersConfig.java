package com.payassist.support;

import org.springframework.boot.autoconfigure.flyway.FlywayConnectionDetails;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL 17 + pgvector (same image as docker-compose) for integration tests. The container is shared by
 * every test class that reuses the same Spring context.
 *
 * <p>Like the Docker stack (ADR 0013, milestone 6.3), Flyway migrates as the schema owner (the container superuser)
 * while the application itself connects as the restricted runtime role {@code payassist_app}, so every integration
 * test also proves the runtime grants are sufficient.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfig {

    public static final DockerImageName IMAGE =
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg17").asCompatibleSubstituteFor("postgres");

    public static final String RUNTIME_ROLE = "payassist_app";
    static final String RUNTIME_PASSWORD = "test-only-app-password";

    @Bean
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(IMAGE).withInitScript("db/test-runtime-role.sql");
    }

    /** The application's DataSource: the runtime role. */
    @Bean
    JdbcConnectionDetails runtimeConnection(PostgreSQLContainer<?> postgres) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return RUNTIME_ROLE;
            }

            @Override
            public String getPassword() {
                return RUNTIME_PASSWORD;
            }

            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }
        };
    }

    /** Test-only owner connection, to check the append-only triggers that also stop the owner. */
    @Bean
    OwnerJdbc ownerJdbc(PostgreSQLContainer<?> postgres) {
        return new OwnerJdbc(new JdbcTemplate(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())));
    }

    /** Flyway: the schema owner. */
    @Bean
    FlywayConnectionDetails ownerConnection(PostgreSQLContainer<?> postgres) {
        return new FlywayConnectionDetails() {
            @Override
            public String getUsername() {
                return postgres.getUsername();
            }

            @Override
            public String getPassword() {
                return postgres.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }
        };
    }
}
