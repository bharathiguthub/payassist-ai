package com.payassist.payment.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payassist.identity.security.SecurityTestConfig;
import com.payassist.payment.PaymentStatus;
import com.payassist.payment.internal.PaymentQueryService;
import com.payassist.payment.internal.PaymentViews.PaymentStats;
import com.payassist.shared.web.PageResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Web-slice tests: real security filter chain + method security, mocked service layer. */
@WebMvcTest(PaymentController.class)
@Import(SecurityTestConfig.class)
@ActiveProfiles("test")
class PaymentControllerSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentQueryService payments;

    @Test
    void anonymousApiCallGets401NotARedirect() throws Exception {
        mvc.perform(get("/api/v1/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-Id"));
        verifyNoInteractions(payments);
    }

    @Test
    void userWithoutPayAssistRoleGets403() throws Exception {
        mvc.perform(get("/api/v1/payments").with(oidcLogin()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(payments);
    }

    @Test
    void careAgentCanSearch() throws Exception {
        when(payments.search(any(), any(), anyInt(), anyInt())).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/v1/payments").param("q", "PAY-0001").with(as("CARE_AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void careAgentCannotSeeStats() throws Exception {
        mvc.perform(get("/api/v1/payments/stats").with(as("CARE_AGENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void supervisorAndAdminCanSeeStats() throws Exception {
        when(payments.stats()).thenReturn(new PaymentStats(0, List.of()));

        mvc.perform(get("/api/v1/payments/stats").with(as("CARE_SUPERVISOR"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/payments/stats").with(as("ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void statusFilterIsBoundToEnum() throws Exception {
        when(payments.search(eq(null), eq(PaymentStatus.FAILED), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/v1/payments").param("status", "FAILED").with(as("CARE_AGENT")))
                .andExpect(status().isOk());
    }

    @Test
    void invalidInputIsRejectedWithProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/payments").param("status", "NOT_A_STATUS").with(as("CARE_AGENT")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/payments").param("size", "1000").with(as("CARE_AGENT")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/payments").param("q", "x' OR 1=1 --").with(as("CARE_AGENT")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        verifyNoInteractions(payments);
    }

    private static RequestPostProcessor as(String role) {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
