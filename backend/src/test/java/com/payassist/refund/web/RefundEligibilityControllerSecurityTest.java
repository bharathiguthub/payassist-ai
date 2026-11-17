package com.payassist.refund.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payassist.identity.security.SecurityTestConfig;
import com.payassist.refund.RefundEligibilityService;
import com.payassist.shared.DomainException;
import java.math.BigDecimal;
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

/** Web slice: real filter chain (CSRF, URL rules) + method security, mocked eligibility service. */
@WebMvcTest(RefundEligibilityController.class)
@Import(SecurityTestConfig.class)
@ActiveProfiles("test")
class RefundEligibilityControllerSecurityTest {

    private static final String URL = "/api/v1/refunds/eligibility-check";
    private static final String BODY = "{\"paymentRef\":\"PAY-000001\",\"amount\":10.50,\"currency\":\"GBP\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RefundEligibilityService eligibility;

    @Test
    void everyCareRoleIncludingReadOnlyAdminMayCheck() throws Exception {
        for (String role : List.of("CARE_AGENT", "CARE_SUPERVISOR", "ADMIN")) {
            mvc.perform(json(post(URL), BODY).with(as(role)).with(csrf())).andExpect(status().isOk());
        }
        verify(eligibility, org.mockito.Mockito.times(3)).check(eq("PAY-000001"), eq(new BigDecimal("10.50")), eq("GBP"));
    }

    @Test
    void anonymousRolelessAndCsrfLessRequestsAreRejected() throws Exception {
        mvc.perform(json(post(URL), BODY).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(json(post(URL), BODY).with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(json(post(URL), BODY).with(as("CARE_AGENT"))).andExpect(status().isForbidden()); // no CSRF token
        verifyNoInteractions(eligibility);
    }

    @Test
    void onlyPostIsSupported() throws Exception {
        mvc.perform(get(URL).with(as("CARE_AGENT"))).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void malformedRequestsAre400() throws Exception {
        for (String body : List.of(
                "{\"amount\":10,\"currency\":\"GBP\"}",                                  // no paymentRef
                "{\"paymentRef\":\"PAY 1\",\"amount\":10,\"currency\":\"GBP\"}",          // bad reference
                "{\"paymentRef\":\"PAY-000001\",\"amount\":10}",                          // amount without currency
                "{\"paymentRef\":\"PAY-000001\",\"amount\":10,\"currency\":\"gbp\"}",     // not an ISO code
                "{\"paymentRef\":\"PAY-000001\",\"amount\":1.123456,\"currency\":\"GBP\"}", // absurd precision
                "{\"paymentRef\":\"PAY-000001\",\"amount\":\"ten\",\"currency\":\"GBP\"}",
                "not json")) {
            mvc.perform(json(post(URL), body).with(as("CARE_AGENT")).with(csrf())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(eligibility);
    }

    @Test
    void amountMayBeOmittedToAskWhatIsRefundable() throws Exception {
        mvc.perform(json(post(URL), "{\"paymentRef\":\"PAY-000001\"}").with(as("CARE_AGENT")).with(csrf()))
                .andExpect(status().isOk());
        verify(eligibility).check(eq("PAY-000001"), isNull(), isNull());
    }

    @Test
    void precisionViolationsFromTheServiceAre422WithCode() throws Exception {
        when(eligibility.check(any(), any(), any())).thenThrow(new DomainException.RuleViolation("INVALID_AMOUNT_SCALE",
                "GBP amounts allow at most 2 decimal places"));
        mvc.perform(json(post(URL), "{\"paymentRef\":\"PAY-000001\",\"amount\":10.001,\"currency\":\"GBP\"}")
                        .with(as("CARE_AGENT")).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT_SCALE"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor as(String role) {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
