package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Separation of credentials in the local stack (ADR 0013 sections 7 and 10), checked on {@code docker-compose.yml}:
 * the long-running backend gets only the runtime role; the owner credentials go to the one-off migrate job; the
 * checkpoint key and ledger only to the on-demand audit-checkpoint tool, which never starts with the stack.
 */
class DeploymentSecretsContractTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) throws Exception {
        Path compose = Path.of("..", "docker-compose.yml");
        try (Reader reader = Files.newBufferedReader(compose)) {
            Map<String, Object> root = new Yaml().load(reader);
            return (Map<String, Object>) ((Map<String, Object>) root.get("services")).get(name);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> env(Map<String, Object> service) {
        return (Map<String, Object>) service.get("environment");
    }

    @Test
    void theBackendHoldsOnlyTheRuntimeRoleAndNoCheckpointMaterial() throws Exception {
        var backend = service("backend");
        var env = env(backend);
        assertThat(env).containsEntry("PAYASSIST_DB_USERNAME", "payassist_app")
                .containsEntry("SPRING_FLYWAY_ENABLED", "false");
        assertThat(env.toString()).doesNotContain("POSTGRES_PASSWORD", "PAYASSIST_AUDIT_CHECKPOINT_KEY",
                "PAYASSIST_DB_AUDITOR_PASSWORD", "MIGRATION");
        assertThat(String.valueOf(backend.get("volumes"))).doesNotContain("checkpoints");
    }

    @Test
    void ownerCredentialsGoOnlyToTheMigrateJob() throws Exception {
        assertThat(env(service("migrate"))).containsKey("PAYASSIST_MIGRATION_PASSWORD")
                .doesNotContainKey("PAYASSIST_AUDIT_CHECKPOINT_KEY");
        for (String name : List.of("backend", "frontend", "audit-checkpoint")) {
            var env = service(name).get("environment");
            assertThat(String.valueOf(env)).as(name).doesNotContain("POSTGRES_PASSWORD");
        }
    }

    @Test
    void theCheckpointToolIsOnDemandReadOnlyAndAloneWithTheLedger() throws Exception {
        var tool = service("audit-checkpoint");
        assertThat(tool.get("profiles")).isEqualTo(List.of("tools"));
        assertThat(env(tool)).containsEntry("PAYASSIST_AUDITOR_USERNAME", "payassist_auditor")
                .containsKey("PAYASSIST_AUDIT_CHECKPOINT_KEY");
        assertThat(String.valueOf(tool.get("volumes"))).contains("./audit-checkpoints:/checkpoints");
        for (String name : List.of("postgres", "keycloak", "migrate", "backend", "frontend")) {
            assertThat(String.valueOf(service(name).get("volumes"))).as(name).doesNotContain("checkpoints");
        }
    }
}
