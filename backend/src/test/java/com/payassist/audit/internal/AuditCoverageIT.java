package com.payassist.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.payassist.support.IntegrationTest;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Audit coverage contract: every state-changing endpoint of the running application must be listed here with the
 * audit action it writes, and that action must be registered in {@link AuditDetailPolicy}. Adding a POST/PUT/PATCH/
 * DELETE endpoint without deciding how it is audited fails this test. (The workflow ITs check the rows themselves.)
 */
@IntegrationTest
class AuditCoverageIT {

    private static final Map<String, String> AUDITED_WRITES = Map.ofEntries(
            Map.entry("POST /api/v1/refunds/eligibility-check", "REFUND_ELIGIBILITY_CHECK"),
            Map.entry("POST /api/v1/refunds", "REFUND_REQUEST_CREATE"),
            Map.entry("POST /api/v1/refunds/{}/approve", "REFUND_REQUEST_APPROVE"),
            Map.entry("POST /api/v1/refunds/{}/reject", "REFUND_REQUEST_REJECT"),
            Map.entry("POST /api/v1/disputes", "DISPUTE_CASE_OPEN"),
            Map.entry("POST /api/v1/disputes/{}/notes", "DISPUTE_NOTE_ADD"),
            Map.entry("POST /api/v1/disputes/{}/evidence", "DISPUTE_EVIDENCE_ADD"),
            Map.entry("POST /api/v1/disputes/{}/start-investigation", "DISPUTE_CASE_TRANSITION"),
            Map.entry("POST /api/v1/disputes/{}/request-review", "DISPUTE_CASE_TRANSITION"),
            Map.entry("POST /api/v1/disputes/{}/return-for-investigation", "DISPUTE_CASE_TRANSITION"),
            Map.entry("POST /api/v1/disputes/{}/approve-submission", "DISPUTE_CASE_TRANSITION"),
            Map.entry("POST /api/v1/disputes/{}/record-outcome", "DISPUTE_CASE_TRANSITION"),
            Map.entry("POST /api/v1/disputes/{}/accept-liability", "DISPUTE_CASE_TRANSITION"),
            Map.entry("POST /api/v1/disputes/{}/withdraw", "DISPUTE_CASE_TRANSITION"));

    private static final Set<RequestMethod> WRITES =
            Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void everyStateChangingEndpointHasARegisteredAuditAction() {
        var found = new TreeMap<String, String>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("com.payassist")) {
                return;
            }
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                if (WRITES.contains(method)) {
                    for (String path : info.getPatternValues()) {
                        found.put(method + " " + path.replaceAll("\\{[^}]+}", "{}"), handler.toString());
                    }
                }
            }
        });
        assertThat(found.keySet()).as("state-changing endpoints (update AUDITED_WRITES deliberately)")
                .containsExactlyInAnyOrderElementsOf(AUDITED_WRITES.keySet());
        assertThat(AuditDetailPolicy.ALLOWED.keySet()).containsAll(AUDITED_WRITES.values());
    }
}
