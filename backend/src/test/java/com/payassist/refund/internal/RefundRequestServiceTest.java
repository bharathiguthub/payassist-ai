package com.payassist.refund.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentHistory.RefundPosition;
import com.payassist.payment.PaymentLedger;
import com.payassist.payment.PaymentMethod;
import com.payassist.payment.PaymentStatus;
import com.payassist.payment.RecordPaymentEvent;
import com.payassist.payment.RecordedPaymentEvent;
import com.payassist.refund.RefundEligibility;
import com.payassist.refund.RefundReason;
import com.payassist.refund.RefundRequestStatus;
import com.payassist.refund.RefundRequestView;
import com.payassist.refund.RefundRequests.CreatedRefundRequest;
import com.payassist.refund.RefundRequests.RequestRefundCommand;
import com.payassist.refund.RejectionReason;
import com.payassist.refund.internal.RefundPolicy.MethodRule;
import com.payassist.shared.Actor;
import com.payassist.shared.AuditRecord;
import com.payassist.shared.DomainException;
import com.payassist.shared.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Actor ALICE = Actor.user("sub-alice", "agent.alice");
    private static final Actor SAM = Actor.user("sub-sam", "sam.supervisor");

    @Mock
    private PaymentLedger ledger;
    @Mock
    private RefundRequestRepository requests;
    @Mock
    private RefundDecisionRepository decisions;
    @Mock
    private ApplicationEventPublisher publisher;
    @Mock
    private PlatformTransactionManager transactionManager;

    private RefundRequestService service;
    private final List<Object> published = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Map<PaymentMethod, MethodRule> methods = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethod m : PaymentMethod.values()) {
            methods.put(m, new MethodRule(true, 120, true));
        }
        var engine = new RefundEligibilityEngine(new RefundPolicy("UNIT-v1", Set.of("GBP"), methods,
                Map.of("GBP", new BigDecimal("1000"))));
        service = new RefundRequestService(ledger, requests, decisions, engine, publisher,
                Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
        org.mockito.Mockito.doAnswer(inv -> published.add(inv.getArgument(0))).when(publisher).publishEvent(any(Object.class));
        when(requests.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(decisions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(requests.sumAmount(any(), anyCollection())).thenReturn(BigDecimal.ZERO);
        when(requests.sumAmountExcluding(any(), anyCollection(), any())).thenReturn(BigDecimal.ZERO);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // --- creation ---------------------------------------------------------------------------------

    @Test
    void createsPendingRequestAfterRevalidatingUnderTheLock() {
        loginAs(ALICE, "CARE_AGENT");
        when(ledger.lockForRefund("PAY-000001")).thenReturn(position(PaymentStatus.SETTLED, true, "100.00"));

        CreatedRefundRequest created = service.requestRefund(command("25", "key-00000001"));

        assertThat(created.replayed()).isFalse();
        RefundRequestView view = created.request();
        assertThat(view.status()).isEqualTo(RefundRequestStatus.PENDING_APPROVAL);
        assertThat(view.amount()).hasToString("25.00");
        assertThat(view.refundRef()).matches("^RFD-[0-9A-F]{12}$");
        assertThat(view.eligibility().decision()).isEqualTo(RefundEligibility.Decision.ELIGIBLE);
        assertThat(view.eligibility().policyVersion()).isEqualTo("UNIT-v1");
        assertThat(view.allowedActions()).isEmpty(); // agents never decide
        assertThat(recordedTypes()).containsExactly(PaymentEventType.REFUND_REQUESTED);
        assertThat(audits()).singleElement().satisfies(a -> {
            assertThat(a.action()).isEqualTo("REFUND_REQUEST_CREATE");
            assertThat(a.outcome()).isEqualTo(AuditRecord.Outcome.SUCCESS);
            assertThat(a.details()).containsEntry("idempotentReplay", "false").containsEntry("status", "PENDING_APPROVAL");
        });
    }

    @Test
    void aboveTheSupervisorLimitTheRequestNeedsEscalation() {
        loginAs(ALICE, "CARE_AGENT");
        when(ledger.lockForRefund("PAY-000001")).thenReturn(position(PaymentStatus.SETTLED, true, "5000.00"));

        RefundRequestView view = service.requestRefund(command("1000.01", "key-00000002")).request();

        assertThat(view.status()).isEqualTo(RefundRequestStatus.ESCALATION_REQUIRED);
        assertThat(view.eligibility().decision()).isEqualTo(RefundEligibility.Decision.MANUAL_REVIEW);
        assertThat(view.eligibility().reasonCodes()).containsExactly("SUPERVISOR_LIMIT_EXCEEDED");
        assertThat(recordedTypes()).containsExactly(PaymentEventType.REFUND_ESCALATED);
    }

    @Test
    void ineligibleRequestIsRefusedWithReasonCodesAndAuditedAsFailed() {
        loginAs(ALICE, "CARE_AGENT");
        when(ledger.lockForRefund("PAY-000001")).thenReturn(position(PaymentStatus.DISPUTED, false, "100.00"));

        assertThatThrownBy(() -> service.requestRefund(command("10.00", "key-00000003")))
                .isInstanceOf(DomainException.RuleViolation.class)
                .satisfies(e -> {
                    DomainException d = (DomainException) e;
                    assertThat(d.getCode()).isEqualTo("REFUND_NOT_ELIGIBLE");
                    assertThat(d.getProperties()).containsEntry("reasons", List.of("PAYMENT_DISPUTED"))
                            .containsEntry("policyVersion", "UNIT-v1");
                });

        verify(requests, never()).saveAndFlush(any());
        verify(ledger, never()).record(any());
        assertThat(audits()).singleElement().satisfies(a -> {
            assertThat(a.outcome()).isEqualTo(AuditRecord.Outcome.FAILED);
            assertThat(a.details()).containsEntry("code", "REFUND_NOT_ELIGIBLE");
        });
    }

    @Test
    void pendingReservationsReduceWhatCanBeRequested() {
        loginAs(ALICE, "CARE_AGENT");
        when(ledger.lockForRefund("PAY-000001")).thenReturn(position(PaymentStatus.SETTLED, true, "100.00"));
        when(requests.sumAmount(eq(PAYMENT_ID), anyCollection())).thenReturn(new BigDecimal("80.00"));

        assertThatThrownBy(() -> service.requestRefund(command("20.01", "key-00000004")))
                .extracting(e -> ((DomainException) e).getProperties().get("reasons"))
                .isEqualTo(List.of("AMOUNT_EXCEEDS_REFUNDABLE", "PENDING_REFUND_IN_PROGRESS"));

        RefundRequestView fits = service.requestRefund(command("20.00", "key-00000005")).request();
        assertThat(fits.status()).isEqualTo(RefundRequestStatus.PENDING_APPROVAL);
        assertThat(fits.eligibility().decision()).isEqualTo(RefundEligibility.Decision.MANUAL_REVIEW);
    }

    @Test
    void sameKeyAndPayloadReplaysTheOriginalRequest() {
        loginAs(ALICE, "CARE_AGENT");
        RefundRequest original = request(ALICE, RefundRequestStatus.PENDING_APPROVAL, "25.00",
                RefundRequestService.fingerprint("PAY-000001", new BigDecimal("25.00"), "GBP", RefundReason.CUSTOMER_REQUEST));
        when(ledger.lockForRefund("PAY-000001")).thenReturn(position(PaymentStatus.SETTLED, true, "100.00"));
        when(requests.findByRequestedBySubjectAndIdempotencyKey("sub-alice", "key-00000006"))
                .thenReturn(Optional.of(original));

        CreatedRefundRequest replay = service.requestRefund(command("25", "key-00000006"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.request().refundRef()).isEqualTo(original.getRefundRef());
        verify(requests, never()).saveAndFlush(any());
        verify(ledger, never()).record(any());
        assertThat(audits()).singleElement().satisfies(a -> assertThat(a.details()).containsEntry("idempotentReplay", "true"));
    }

    @Test
    void sameKeyWithADifferentPayloadIsRefused() {
        loginAs(ALICE, "CARE_AGENT");
        RefundRequest original = request(ALICE, RefundRequestStatus.PENDING_APPROVAL, "25.00", "other-fingerprint");
        when(ledger.lockForRefund("PAY-000001")).thenReturn(position(PaymentStatus.SETTLED, true, "100.00"));
        when(requests.findByRequestedBySubjectAndIdempotencyKey("sub-alice", "key-00000007"))
                .thenReturn(Optional.of(original));

        assertCode(() -> service.requestRefund(command("30.00", "key-00000007")), "IDEMPOTENCY_KEY_REUSED");
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void fingerprintIsCanonicalOverAmountScale() {
        String a = RefundRequestService.fingerprint("PAY-000001", new BigDecimal("10.00"), "GBP", RefundReason.GOODWILL);
        assertThat(a).hasSize(64)
                .isEqualTo(RefundRequestService.fingerprint("PAY-000001", new BigDecimal("10.00"), "GBP", RefundReason.GOODWILL))
                .isNotEqualTo(RefundRequestService.fingerprint("PAY-000001", new BigDecimal("10.01"), "GBP",
                        RefundReason.GOODWILL))
                .isNotEqualTo(RefundRequestService.fingerprint("PAY-000001", new BigDecimal("10.00"), "GBP",
                        RefundReason.CUSTOMER_REQUEST));
    }

    @Test
    void malformedInputIsRejectedBeforeAnyRead() {
        loginAs(ALICE, "CARE_AGENT");
        assertCode(() -> service.requestRefund(command("10.001", "key-00000008")), "INVALID_AMOUNT_SCALE");
        assertCode(() -> service.requestRefund(command("10.00", "short")), "IDEMPOTENCY_KEY_INVALID");
        verify(ledger, never()).lockForRefund(any());
    }

    // --- decisions --------------------------------------------------------------------------------

    @Test
    void supervisorApprovesRecordsTheRefundOnceAndConsumesTheReservation() {
        loginAs(SAM, "CARE_SUPERVISOR");
        RefundRequest pending = givenLocked(request(ALICE, RefundRequestStatus.PENDING_APPROVAL, "25.00", "fp"),
                PaymentStatus.SETTLED, true);
        UUID ledgerEventId = UUID.randomUUID();
        when(ledger.record(any())).thenReturn(new RecordedPaymentEvent(ledgerEventId, "PAY-000001", 6,
                PaymentEventType.REFUND_RECORDED, PaymentStatus.SETTLED, PaymentStatus.PARTIALLY_REFUNDED, 7));

        RefundRequestView view = service.approve(pending.getRefundRef(), 0L);

        assertThat(view.status()).isEqualTo(RefundRequestStatus.APPROVED);
        assertThat(view.decision().decidedBy()).isEqualTo("sam.supervisor");
        assertThat(view.decision().ledgerEventRecorded()).isTrue();
        assertThat(view.allowedActions()).isEmpty();
        assertThat(recordedTypes()).containsExactly(PaymentEventType.REFUND_RECORDED);
        ArgumentCaptor<RefundDecision> decision = ArgumentCaptor.forClass(RefundDecision.class);
        verify(decisions).save(decision.capture());
        assertThat(decision.getValue().getLedgerEventId()).isEqualTo(ledgerEventId);
        assertThat(decision.getValue().getDecidedBySubject()).isEqualTo("sub-sam");
    }

    @Test
    void requesterCannotApproveTheirOwnRequest() {
        loginAs(SAM, "CARE_SUPERVISOR");
        RefundRequest own = givenLocked(request(SAM, RefundRequestStatus.PENDING_APPROVAL, "25.00", "fp"),
                PaymentStatus.SETTLED, true);

        assertCode(() -> service.approve(own.getRefundRef(), null), "SELF_APPROVAL_FORBIDDEN");
        assertCode(() -> service.reject(own.getRefundRef(), RejectionReason.OUTSIDE_POLICY, null),
                "SELF_REJECTION_FORBIDDEN");
        verify(ledger, never()).record(any());
        assertThat(audits()).hasSize(2).allSatisfy(a -> assertThat(a.outcome()).isEqualTo(AuditRecord.Outcome.DENIED));
    }

    @Test
    void escalatedRequestsCannotBeApprovedButCanBeRejected() {
        loginAs(SAM, "CARE_SUPERVISOR");
        RefundRequest escalated = givenLocked(request(ALICE, RefundRequestStatus.ESCALATION_REQUIRED, "1500.00", "fp"),
                PaymentStatus.SETTLED, true);
        assertThat(service.getRefundStatus(escalated.getRefundRef()).allowedActions())
                .containsExactly(RefundRequestView.Action.REJECT);

        assertCode(() -> service.approve(escalated.getRefundRef(), null), "ESCALATION_REQUIRED");
        RefundRequestView rejected = service.reject(escalated.getRefundRef(), RejectionReason.ESCALATION_NOT_AVAILABLE, null);
        assertThat(rejected.status()).isEqualTo(RefundRequestStatus.REJECTED);
        assertThat(rejected.decision().reasonCode()).isEqualTo("ESCALATION_NOT_AVAILABLE");
        assertThat(recordedTypes()).containsExactly(PaymentEventType.REFUND_REJECTED);
    }

    @Test
    void decidedRequestsCannotBeDecidedAgain() {
        loginAs(SAM, "CARE_SUPERVISOR");
        RefundRequest approved = givenLocked(request(ALICE, RefundRequestStatus.APPROVED, "25.00", "fp"),
                PaymentStatus.PARTIALLY_REFUNDED, true);

        assertThatThrownBy(() -> service.approve(approved.getRefundRef(), null))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> service.reject(approved.getRefundRef(), RejectionReason.OUTSIDE_POLICY, null))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(ledger, never()).record(any());
        verify(decisions, never()).save(any());
    }

    @Test
    void pendingRequestAboveTheCurrentLimitCannotBeApproved() {
        loginAs(SAM, "CARE_SUPERVISOR");
        RefundRequest big = givenLocked(request(ALICE, RefundRequestStatus.PENDING_APPROVAL, "1000.01", "fp"),
                PaymentStatus.SETTLED, true);
        assertCode(() -> service.approve(big.getRefundRef(), null), "APPROVAL_LIMIT_EXCEEDED");
    }

    @Test
    void staleVersionIsAConcurrencyFailure() {
        loginAs(SAM, "CARE_SUPERVISOR");
        RefundRequest pending = givenLocked(request(ALICE, RefundRequestStatus.PENDING_APPROVAL, "25.00", "fp"),
                PaymentStatus.SETTLED, true);
        assertThatThrownBy(() -> service.approve(pending.getRefundRef(), 3L))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(audits()).singleElement().satisfies(a -> assertThat(a.details()).containsEntry("code",
                "CONCURRENT_MODIFICATION"));
    }

    @Test
    void approvalRevalidatesAgainstCurrentPaymentState() {
        loginAs(SAM, "CARE_SUPERVISOR");
        RefundRequest pending = givenLocked(request(ALICE, RefundRequestStatus.PENDING_APPROVAL, "25.00", "fp"),
                PaymentStatus.DISPUTED, false);

        assertThatThrownBy(() -> service.approve(pending.getRefundRef(), null))
                .satisfies(e -> {
                    assertThat(((DomainException) e).getCode()).isEqualTo("REFUND_NO_LONGER_ELIGIBLE");
                    assertThat(((DomainException) e).getProperties()).containsEntry("reasons", List.of("PAYMENT_DISPUTED"));
                });
        verify(ledger, never()).record(any());
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static RequestRefundCommand command(String amount, String key) {
        return new RequestRefundCommand("pay-000001", new BigDecimal(amount), "GBP", RefundReason.CUSTOMER_REQUEST, key);
    }

    private static RefundPosition position(PaymentStatus status, boolean refundable, String amount) {
        Instant captured = NOW.minus(Duration.ofDays(5));
        return new RefundPosition(PAYMENT_ID, "PAY-000001", new BigDecimal(amount), "GBP", status, PaymentMethod.CARD,
                captured, captured, new BigDecimal("0.00"), 0, refundable, true, 2);
    }

    private static RefundRequest request(Actor requester, RefundRequestStatus status, String amount, String fingerprint) {
        RefundRequest r = new RefundRequest(UUID.randomUUID(), RefundRequestService.newRefundRef(), PAYMENT_ID,
                "PAY-000001", new BigDecimal(amount), "GBP", RefundReason.CUSTOMER_REQUEST,
                status.isFinal() ? RefundRequestStatus.PENDING_APPROVAL : status,
                RefundEligibility.Decision.ELIGIBLE, "WITHIN_POLICY", "UNIT-v1", requester, NOW.minusSeconds(60),
                "corr", "key-original", fingerprint);
        if (status.isFinal()) {
            r.transitionTo(status, NOW.minusSeconds(30));
        }
        return r;
    }

    private RefundRequest givenLocked(RefundRequest r, PaymentStatus paymentStatus, boolean refundable) {
        when(requests.findPaymentRefByRefundRef(r.getRefundRef())).thenReturn(Optional.of("PAY-000001"));
        when(requests.findByRefundRefForUpdate(r.getRefundRef())).thenReturn(Optional.of(r));
        when(requests.findByRefundRef(r.getRefundRef())).thenReturn(Optional.of(r));
        when(ledger.lockForRefund("PAY-000001")).thenReturn(position(paymentStatus, refundable, "5000.00"));
        return r;
    }

    private List<PaymentEventType> recordedTypes() {
        ArgumentCaptor<RecordPaymentEvent> captor = ArgumentCaptor.forClass(RecordPaymentEvent.class);
        verify(ledger, org.mockito.Mockito.atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(RecordPaymentEvent::type).toList();
    }

    private List<AuditRecord> audits() {
        return published.stream().filter(AuditRecord.class::isInstance).map(AuditRecord.class::cast).toList();
    }

    private static void loginAs(Actor actor, String role) {
        var user = new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, new OidcIdToken("t", NOW, NOW.plusSeconds(300),
                Map.of("sub", actor.subject(), "preferred_username", actor.username())));
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(user, AuthorityUtils.createAuthorityList("ROLE_" + role), "keycloak"));
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo(code);
    }
}
