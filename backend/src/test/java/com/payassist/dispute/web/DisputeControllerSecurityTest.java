package com.payassist.dispute.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payassist.dispute.DisputeCaseWork;
import com.payassist.dispute.DisputeCaseWork.CreatedDispute;
import com.payassist.dispute.DisputeOutcome;
import com.payassist.dispute.DisputeQueries;
import com.payassist.dispute.DisputeReasonCategory;
import com.payassist.dispute.DisputeResolutions;
import com.payassist.dispute.DisputeStatus;
import com.payassist.dispute.DisputeView;
import com.payassist.dispute.WithdrawalReason;
import com.payassist.identity.security.SecurityTestConfig;
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

/** Web slice: real filter chain (URL rules, CSRF) + method security, mocked dispute services. */
@WebMvcTest(DisputeController.class)
@Import(SecurityTestConfig.class)
@ActiveProfiles("test")
class DisputeControllerSecurityTest {

    private static final String REF = "DSP-0123456789AB";
    private static final String BASE = "/api/v1/disputes/" + REF;
    private static final String OPEN_BODY = "{\"paymentRef\":\"PAY-000001\",\"reasonCategory\":\"PRODUCT_NOT_RECEIVED\","
            + "\"responseDueAt\":\"2026-12-01T00:00:00Z\"}";
    private static final String VERSION = "{\"expectedVersion\":2}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DisputeQueries queries;
    @MockitoBean
    private DisputeCaseWork work;
    @MockitoBean
    private DisputeResolutions resolutions;

    @Test
    void agentsAndSupervisorsOpenCasesAdminsAndRolelessUsersCannot() throws Exception {
        when(work.openDispute(any())).thenReturn(new CreatedDispute(view(), false));
        for (String role : List.of("CARE_AGENT", "CARE_SUPERVISOR")) {
            mvc.perform(json(post("/api/v1/disputes"), OPEN_BODY).header("Idempotency-Key", "key-12345678")
                            .with(as(role)).with(csrf()))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/disputes/" + REF))
                    .andExpect(header().string("Idempotent-Replayed", "false"));
        }
        for (RequestPostProcessor denied : List.of(as("ADMIN"), oidcLogin())) {
            mvc.perform(json(post("/api/v1/disputes"), OPEN_BODY).header("Idempotency-Key", "key-12345678")
                    .with(denied).with(csrf())).andExpect(status().isForbidden());
        }
        mvc.perform(json(post("/api/v1/disputes"), OPEN_BODY).header("Idempotency-Key", "key-12345678").with(csrf()))
                .andExpect(status().isUnauthorized());
        verify(work, org.mockito.Mockito.times(2)).openDispute(any());
    }

    @Test
    void creationNeedsCsrfIdempotencyKeyAndAValidBody() throws Exception {
        mvc.perform(json(post("/api/v1/disputes"), OPEN_BODY).header("Idempotency-Key", "key-12345678")
                .with(as("CARE_AGENT"))).andExpect(status().isForbidden()); // no CSRF
        mvc.perform(json(post("/api/v1/disputes"), OPEN_BODY).with(as("CARE_AGENT")).with(csrf()))
                .andExpect(status().isBadRequest()); // no key
        for (String body : List.of("{\"paymentRef\":\"PAY-000001\"}",
                "{\"paymentRef\":\"PAY-000001\",\"reasonCategory\":\"VISA_10_4\"}",
                "{\"paymentRef\":\"PAY 1\",\"reasonCategory\":\"OTHER\"}",
                "{\"paymentRef\":\"PAY-000001\",\"reasonCategory\":\"OTHER\",\"networkReasonCode\":\"has spaces\"}",
                "{\"paymentRef\":\"PAY-000001\",\"reasonCategory\":\"OTHER\",\"responseDueAt\":\"next week\"}")) {
            mvc.perform(json(post("/api/v1/disputes"), body).header("Idempotency-Key", "key-12345678")
                    .with(as("CARE_AGENT")).with(csrf())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(work);
    }

    @Test
    void caseWorkIsForAgentsAndSupervisors() throws Exception {
        when(work.addNote(anyString(), anyString(), anyString())).thenReturn(view());
        when(work.startInvestigation(anyString(), anyLong())).thenReturn(view());
        mvc.perform(json(post(BASE + "/notes"), "{\"body\":\"Called the customer.\"}").header("Idempotency-Key",
                "note-12345678").with(as("CARE_AGENT")).with(csrf())).andExpect(status().isOk());
        mvc.perform(json(post(BASE + "/start-investigation"), VERSION).with(as("CARE_AGENT")).with(csrf()))
                .andExpect(status().isOk());
        verify(work).startInvestigation(REF, 2L);

        mvc.perform(json(post(BASE + "/notes"), "{\"body\":\"x\"}").header("Idempotency-Key", "note-12345678")
                .with(as("ADMIN")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(json(post(BASE + "/start-investigation"), "{}").with(as("CARE_AGENT")).with(csrf()))
                .andExpect(status().isBadRequest()); // expectedVersion required
        mvc.perform(json(post(BASE + "/notes"), "{\"body\":\"x\"}").with(as("CARE_AGENT")).with(csrf()))
                .andExpect(status().isBadRequest()); // Idempotency-Key required
    }

    @Test
    void resolutionsAreSupervisorOnly() throws Exception {
        when(resolutions.recordOutcome(anyString(), any(), anyLong())).thenReturn(view());
        when(resolutions.withdraw(anyString(), any(), anyLong())).thenReturn(view());
        mvc.perform(json(post(BASE + "/record-outcome"), "{\"outcome\":\"WON\",\"expectedVersion\":2}")
                .with(as("CARE_SUPERVISOR")).with(csrf())).andExpect(status().isOk());
        verify(resolutions).recordOutcome(REF, DisputeOutcome.WON, 2L);
        mvc.perform(json(post(BASE + "/withdraw"), "{\"reason\":\"CUSTOMER_WITHDREW\",\"expectedVersion\":0}")
                .with(as("CARE_SUPERVISOR")).with(csrf())).andExpect(status().isOk());
        verify(resolutions).withdraw(REF, WithdrawalReason.CUSTOMER_WITHDREW, 0L);

        for (String path : List.of("/return-for-investigation", "/approve-submission", "/record-outcome",
                "/accept-liability", "/withdraw")) {
            for (String role : List.of("CARE_AGENT", "ADMIN")) {
                mvc.perform(json(post(BASE + path), "{\"outcome\":\"WON\",\"reason\":\"CUSTOMER_WITHDREW\","
                        + "\"expectedVersion\":2}").with(as(role)).with(csrf())).andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void everyCareRoleCanReadAndUnlistedRoutesAreDenied() throws Exception {
        when(queries.getDispute(REF)).thenReturn(view());
        when(queries.listDisputes(any(), anyInt(), anyInt())).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        for (String role : List.of("CARE_AGENT", "CARE_SUPERVISOR", "ADMIN")) {
            mvc.perform(get(BASE).with(as(role))).andExpect(status().isOk());
            mvc.perform(get("/api/v1/disputes").param("status", "PENDING_REVIEW").param("overdue", "true")
                    .with(as(role))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/disputes").with(oidcLogin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/disputes/not-a-ref").with(as("CARE_AGENT"))).andExpect(status().isBadRequest());
        mvc.perform(delete(BASE).with(as("CARE_SUPERVISOR")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/reopen").with(as("CARE_SUPERVISOR")).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void businessRuleDenialsAreProblemDetails() throws Exception {
        when(resolutions.approveSubmission(anyString(), anyLong())).thenThrow(new DomainException.Forbidden(
                "SELF_REVIEW_FORBIDDEN", "The evidence submission must be approved by someone other than the person "
                        + "who requested review"));
        mvc.perform(json(post(BASE + "/approve-submission"), VERSION).with(as("CARE_SUPERVISOR")).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_REVIEW_FORBIDDEN"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor as(String role) {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static DisputeView view() {
        Instant t = Instant.parse("2026-11-16T12:00:00Z");
        return new DisputeView("1.0", REF, "PAY-000001", DisputeReasonCategory.PRODUCT_NOT_RECEIVED, null,
                new BigDecimal("706.68"), "GBP", DisputeStatus.OPEN, "PAYASSIST", null, false, "agent.alice", t, null, t,
                List.of(), List.of(), List.of(), List.of(), List.of(), 0);
    }
}
