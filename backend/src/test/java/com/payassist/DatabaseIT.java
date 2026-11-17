package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payassist.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class DatabaseIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Test
    void flywayAppliedSchemaAndSyntheticSeed() {
        assertThat(jdbc.queryForObject("select count(*) from customers", Long.class)).isEqualTo(40);
        assertThat(jdbc.queryForObject("select count(*) from payments", Long.class)).isEqualTo(400);
        assertThat(jdbc.queryForObject(
                "select count(*) from customers where email not like '%@example.com'", Long.class)).isZero();
    }

    @Test
    void pgvectorExtensionIsAvailableForTheRagPhaseButNotYetInstalled() {
        assertThat(jdbc.queryForObject(
                "select count(*) from pg_available_extensions where name = 'vector'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from pg_extension where extname = 'vector'", Long.class)).isZero();
    }

    @Test
    void healthEndpointIsPublicAndRevealsNoDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
