package com.payassist.refund.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payassist.identity.security.SecurityTestConfig;
import com.payassist.refund.RefundApprovals;
import com.payassist.refund.RefundEligibility;
import com.payassist.refund.RefundReason;
import com.payassist.refund.RefundRequestStatus;
import com.payassist.refund.RefundRequestView;
import com.payassist.refund.RefundRequests;
import com.payassist.refund.RefundRequests.CreatedRefundRequest;
import com.payassist.refund.RejectionReason;
import com.payassist.shared.DomainException;
import com.payassist.shared.web.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Web slice: real filter chain (URL rules, CSRF) + method security, mocked refund services. */
@WebMvcTest(RefundRequestController.class)
@Import(SecurityTestConfig.class)
@ActiveProfiles("test")
class RefundRequestControllerSecurityTest {

    private static final String REF = "RFD-0123456789AB";
    private static final String CREATE_BODY =
            "{\"paymentRef\":\"PAY-000001\",\"amount\":\"25.00\",\"currency\":\"GBP\",\"reason\":\"CUSTOMER_REQUEST\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RefundRequests refunds;

    @MockitoBean
    private RefundApprovals approvals;

    @Test
    void agentsAndSupervisorsCreateAdminsAndRolelessUsersCannot() throws Exception {
        when(refunds.requestRefund(any())).thenReturn(new CreatedRefundRequest(view(), false));

        for (String role : List.of("CARE_AGENT", "CARE_SUPERVISOR")) {
            mvc.perform(create(CREATE_BODY, "key-12345678").with(as(role)).with(csrf()))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/refunds/" + REF))
                    .andExpect(header().string("Idempotent-Replayed", "false"))
                    .andExpect(jsonPath("$.refundRef").value(REF));
        }
        mvc.perform(create(CREATE_BODY, "key-12345678").with(as("ADMIN")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(create(CREATE_BODY, "key-12345678").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(create(CREATE_BODY, "key-12345678").with(csrf())).andExpect(status().isUnauthorized());
        verify(refunds, org.mockito.Mockito.times(2)).requestRefund(any());
    }

    @Test
    void replayIs200WithHeader() throws Exception {
        when(refunds.requestRefund(any())).thenReturn(new CreatedRefundRequest(view(), true));
        mvc.perform(create(CREATE_BODY, "key-12345678").with(as("CARE_AGENT")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void creationNeedsCsrfAndAWellFormedIdempotencyKey() throws Exception {
        mvc.perform(create(CREATE_BODY, "key-12345678").with(as("CARE_AGENT"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/refunds").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY)
                .with(as("CARE_AGENT")).with(csrf())).andExpect(status().isBadRequest()); // header missing
        mvc.perform(create(CREATE_BODY, "short").with(as("CARE_AGENT")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(create(CREATE_BODY, "has spaces in it").with(as("CARE_AGENT")).with(csrf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(refunds);
    }

    @Test
    void malformedBodiesAre400() throws Exception {
        for (String body : List.of(
                "{\"paymentRef\":\"PAY-000001\",\"amount\":\"25.00\",\"currency\":\"GBP\"}",                    // no reason
                "{\"paymentRef\":\"PAY-000001\",\"amount\":\"25.00\",\"currency\":\"GBP\",\"reason\":\"FREE TEXT\"}",
                "{\"paymentRef\":\"PAY-000001\",\"currency\":\"GBP\",\"reason\":\"GOODWILL\"}",                   // no amount
                "{\"paymentRef\":\"PAY 1\",\"amount\":1,\"currency\":\"GBP\",\"reason\":\"GOODWILL\"}")) {
            mvc.perform(create(body, "key-12345678").with(as("CARE_AGENT")).with(csrf())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(refunds);
    }

    @Test
    void onlySupervisorsMayApproveOrReject() throws Exception {
        when(approvals.approve(any(), any())).thenReturn(view());
        when(approvals.reject(any(), any(), any())).thenReturn(view());

        mvc.perform(json(post("/api/v1/refunds/" + REF + "/approve"), "{\"expectedVersion\":3}")
                .with(as("CARE_SUPERVISOR")).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/refunds/" + REF + "/approve").with(as("CARE_SUPERVISOR")).with(csrf()))
                .andExpect(status().isOk()); // body optional
        mvc.perform(json(post("/api/v1/refunds/" + REF + "/reject"), "{\"reason\":\"OUTSIDE_POLICY\"}")
                .with(as("CARE_SUPERVISOR")).with(csrf())).andExpect(status().isOk());
        verify(approvals).approve(REF, 3L);
        verify(approvals).approve(eq(REF), isNull());
        verify(approvals).reject(REF, RejectionReason.OUTSIDE_POLICY, null);

        for (String role : List.of("CARE_AGENT", "ADMIN")) {
            mvc.perform(post("/api/v1/refunds/" + REF + "/approve").with(as(role)).with(csrf()))
                    .andExpect(status().isForbidden());
            mvc.perform(json(post("/api/v1/refunds/" + REF + "/reject"), "{\"reason\":\"OUTSIDE_POLICY\"}")
                    .with(as(role)).with(csrf())).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/refunds/" + REF + "/approve").with(as("CARE_SUPERVISOR")))
                .andExpect(status().isForbidden()); // no CSRF token
        mvc.perform(json(post("/api/v1/refunds/" + REF + "/reject"), "{}").with(as("CARE_SUPERVISOR")).with(csrf()))
                .andExpect(status().isBadRequest()); // reason required
    }

    @Test
    void everyCareRoleIncludingAdminCanRead() throws Exception {
        when(refunds.getRefundStatus(REF)).thenReturn(view());
        when(refunds.listRefunds(any(), anyInt(), anyInt())).thenReturn(new PageResponse<>(List.of(view()), 0, 20, 1, 1));

        for (String role : List.of("CARE_AGENT", "CARE_SUPERVISOR", "ADMIN")) {
            mvc.perform(get("/api/v1/refunds/" + REF).with(as(role))).andExpect(status().isOk());
            mvc.perform(get("/api/v1/refunds").param("status", "PENDING_APPROVAL", "ESCALATION_REQUIRED")
                    .with(as(role))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        }
        mvc.perform(get("/api/v1/refunds").with(oidcLogin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/refunds")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/refunds/not-a-ref").with(as("CARE_AGENT"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/refunds").param("status", "NOT_A_STATUS").with(as("CARE_AGENT")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unlistedRefundRoutesAreDenied() throws Exception {
        mvc.perform(put("/api/v1/refunds/" + REF).with(as("CARE_SUPERVISOR")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/refunds/" + REF + "/execute").with(as("CARE_SUPERVISOR")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void businessRuleDenialsAreProblemDetailsWithCodes() throws Exception {
        when(approvals.approve(any(), any())).thenThrow(new DomainException.Forbidden("SELF_APPROVAL_FORBIDDEN",
                "A refund request cannot be approved by the person who requested it"));
        mvc.perform(post("/api/v1/refunds/" + REF + "/approve").with(as("CARE_SUPERVISOR")).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"))
                .andExpect(jsonPath("$.title").value("Action not permitted"));

        when(refunds.requestRefund(any())).thenThrow(new DomainException.RuleViolation("REFUND_NOT_ELIGIBLE",
                "The refund is not eligible: PAYMENT_DISPUTED",
                java.util.Map.of("reasons", List.of("PAYMENT_DISPUTED"), "policyVersion", "v1")));
        mvc.perform(create(CREATE_BODY, "key-12345678").with(as("CARE_AGENT")).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.reasons[0]").value("PAYMENT_DISPUTED"))
                .andExpect(jsonPath("$.policyVersion").value("v1"));
    }

    private static MockHttpServletRequestBuilder create(String body, String key) {
        return json(post("/api/v1/refunds"), body).header("Idempotency-Key", key);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor as(String role) {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static RefundRequestView view() {
        return new RefundRequestView("1.0", REF, "PAY-000001", new BigDecimal("25.00"), "GBP",
                RefundReason.CUSTOMER_REQUEST, RefundRequestStatus.PENDING_APPROVAL,
                new RefundRequestView.Eligibility(RefundEligibility.Decision.ELIGIBLE, List.of("WITHIN_POLICY"), "v1"),
                "agent.alice", Instant.parse("2026-11-16T12:00:00Z"), "corr", null, List.of(), 0);
    }
}
