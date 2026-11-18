package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payassist.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class PaymentApiIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void searchesByPaymentRefPrefix() throws Exception {
        mvc.perform(get("/api/v1/payments").param("q", "pay-00001").with(agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(10)) // PAY-000010 .. PAY-000019
                .andExpect(jsonPath("$.content[0].customerRef").exists());
    }

    @Test
    void searchesByCustomerRef() throws Exception {
        mvc.perform(get("/api/v1/payments").param("q", "CUS-00001").param("size", "100").with(agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(10)) // 400 payments spread over 40 customers
                .andExpect(jsonPath("$.content[*].customerRef", everyItem(is("CUS-00001"))));
    }

    @Test
    void filtersByStatus() throws Exception {
        mvc.perform(get("/api/v1/payments").param("status", "FAILED").param("size", "100").with(agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].status", everyItem(is("FAILED"))));
    }

    @Test
    void wildcardCharactersAreNotInterpretedAsPatterns() throws Exception {
        mvc.perform(get("/api/v1/payments").param("q", "%").with(agent()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailsMaskEmailAndAreAudited() throws Exception {
        long before = auditCount("PAY-000042");

        mvc.perform(get("/api/v1/payments/PAY-000042").with(agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentRef").value("PAY-000042"))
                .andExpect(jsonPath("$.customer.maskedEmail").value("c***@example.com"))
                .andExpect(jsonPath("$.customer.email").doesNotExist());

        assertThat(auditCount("PAY-000042")).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject(
                "select actor from audit_events where resource_id = 'PAY-000042' order by id desc limit 1",
                String.class)).isEqualTo("agent.alice");
    }

    @Test
    void unknownPaymentReturnsProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/payments/PAY-999999").with(agent()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }

    @Test
    void supervisorSeesStatsOverAllSeededPayments() throws Exception {
        mvc.perform(get("/api/v1/payments/stats")
                        .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_CARE_SUPERVISOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPayments").value(400));
    }

    @Test
    void adminSeesAuditTrail() throws Exception {
        mvc.perform(get("/api/v1/payments/PAY-000007").with(agent())).andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/audit-events").param("resourceId", "PAY-000007")
                        .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("VIEW"))
                .andExpect(jsonPath("$.content[0].resourceType").value("PAYMENT"));
    }

    private long auditCount(String paymentRef) {
        return jdbc.queryForObject("select count(*) from audit_events where resource_id = ?", Long.class, paymentRef);
    }

    private static RequestPostProcessor agent() {
        return oidcLogin()
                .idToken(t -> t.subject("agent.alice")) // test principal name defaults to the 'sub' claim
                .authorities(new SimpleGrantedAuthority("ROLE_CARE_AGENT"));
    }
}
