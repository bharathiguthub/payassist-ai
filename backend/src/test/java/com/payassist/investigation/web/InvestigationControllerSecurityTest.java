package com.payassist.investigation.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payassist.identity.security.SecurityTestConfig;
import com.payassist.investigation.InvestigationService;
import com.payassist.investigation.InvestigationSummary;
import com.payassist.investigation.InvestigationSummary.DisputeFacts;
import com.payassist.investigation.InvestigationSummary.DisputeState;
import com.payassist.investigation.InvestigationSummary.HistoryCoverage;
import com.payassist.investigation.InvestigationSummary.Milestones;
import com.payassist.investigation.InvestigationSummary.MoneyFacts;
import com.payassist.investigation.InvestigationSummary.PaymentFacts;
import com.payassist.payment.PaymentMethod;
import com.payassist.payment.PaymentStatus;
import com.payassist.shared.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Web-slice tests: real security filter chain + method security, mocked investigation service. */
@WebMvcTest(InvestigationController.class)
@Import(SecurityTestConfig.class)
@ActiveProfiles("test")
class InvestigationControllerSecurityTest {

    private static final String URL = "/api/v1/payments/PAY-000001/investigation-summary";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InvestigationService investigations;

    @Test
    void careAgentSupervisorAndReadOnlyAdminCanReadTheSummary() throws Exception {
        when(investigations.summarize("PAY-000001")).thenReturn(sample());

        for (String role : List.of("CARE_AGENT", "CARE_SUPERVISOR", "ADMIN")) {
            mvc.perform(get(URL).with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                    .andExpect(jsonPath("$.paymentRef").value("PAY-000001"))
                    .andExpect(jsonPath("$.money.paymentAmount").value(100.00))
                    .andExpect(jsonPath("$.basis").value(InvestigationSummary.BASIS));
        }
    }

    @Test
    void anonymousAndRolelessUsersAreDenied() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(get(URL).with(oidcLogin())).andExpect(status().isForbidden());
        mvc.perform(get(URL).with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_offline_access"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(investigations);
    }

    @Test
    void endpointIsReadOnly() throws Exception {
        mvc.perform(post(URL).with(as("CARE_SUPERVISOR")).with(csrf())).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(investigations);
    }

    @Test
    void invalidReferenceIs400AndMissingPaymentIs404() throws Exception {
        mvc.perform(get("/api/v1/payments/PAY_0001!/investigation-summary").with(as("CARE_AGENT")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(investigations);

        when(investigations.summarize("PAY-999999")).thenThrow(new ResourceNotFoundException("Payment", "PAY-999999"));
        mvc.perform(get("/api/v1/payments/PAY-999999/investigation-summary").with(as("CARE_AGENT")))
                .andExpect(status().isNotFound());
    }

    private static InvestigationSummary sample() {
        Instant at = Instant.parse("2026-05-18T00:53:00Z");
        BigDecimal amount = new BigDecimal("100.00");
        return new InvestigationSummary(InvestigationSummary.SCHEMA_VERSION, "PAY-000001", at, 4,
                InvestigationSummary.BASIS,
                new PaymentFacts(amount, "GBP", PaymentStatus.SETTLED, "Funds settled to the merchant.", false,
                        PaymentMethod.CARD, "Demo Travel Co", null, null, at, at, null),
                new Milestones(at, at, at, at, null, null, null, null, null),
                new MoneyFacts("GBP", amount, new BigDecimal("0.00"), amount, 0),
                new DisputeFacts(DisputeState.NONE, null, null, null, null),
                new HistoryCoverage(4, true, at), List.of(), Set.of());
    }

    private static RequestPostProcessor as(String role) {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
