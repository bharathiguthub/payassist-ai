package com.payassist.dispute.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payassist.dispute.DisputeAction;
import com.payassist.dispute.DisputeReasonCategory;
import com.payassist.dispute.DisputeStatus;
import com.payassist.dispute.internal.DisputeTransitions.Duty;
import com.payassist.dispute.internal.DisputeTransitions.Performer;
import com.payassist.dispute.internal.DisputeTransitions.Transition;
import com.payassist.payment.PaymentEventType;
import com.payassist.shared.Actor;
import com.payassist.shared.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class DisputeTransitionsTest {

    private static final Instant T = Instant.parse("2026-11-16T12:00:00Z");
    private static final Actor ALICE = Actor.user("sub-alice", "agent.alice");
    private static final Actor SAM = Actor.user("sub-sam", "sam.supervisor");
    private static final Actor TARA = Actor.user("sub-tara", "tara.supervisor");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** The full specification, in one place. */
    @Test
    void tableMatchesTheApprovedStateMachine() {
        Map<Transition, Set<DisputeStatus>> from = Map.of(
                Transition.START_INVESTIGATION, EnumSet.of(DisputeStatus.OPEN),
                Transition.REQUEST_REVIEW, EnumSet.of(DisputeStatus.UNDER_INVESTIGATION),
                Transition.RETURN_FOR_INVESTIGATION, EnumSet.of(DisputeStatus.PENDING_REVIEW),
                Transition.APPROVE_SUBMISSION, EnumSet.of(DisputeStatus.PENDING_REVIEW),
                Transition.RECORD_WON, EnumSet.of(DisputeStatus.EVIDENCE_SUBMITTED),
                Transition.RECORD_LOST, EnumSet.of(DisputeStatus.EVIDENCE_SUBMITTED),
                Transition.ACCEPT_LIABILITY, EnumSet.of(DisputeStatus.OPEN, DisputeStatus.UNDER_INVESTIGATION,
                        DisputeStatus.PENDING_REVIEW),
                Transition.WITHDRAW, EnumSet.of(DisputeStatus.OPEN, DisputeStatus.UNDER_INVESTIGATION,
                        DisputeStatus.PENDING_REVIEW, DisputeStatus.EVIDENCE_SUBMITTED));
        for (Transition t : Transition.values()) {
            for (DisputeStatus s : DisputeStatus.values()) {
                assertThat(t.allowedFrom(s)).as(t + " from " + s).isEqualTo(from.get(t).contains(s));
            }
        }
        assertThat(Transition.RECORD_WON.to).isEqualTo(DisputeStatus.WON);
        assertThat(Transition.RECORD_LOST.to).isEqualTo(DisputeStatus.LOST);
        assertThat(Transition.ACCEPT_LIABILITY.to).isEqualTo(DisputeStatus.LOST);
        assertThat(Transition.WITHDRAW.to).isEqualTo(DisputeStatus.WITHDRAWN);
    }

    @Test
    void onlyOutcomesChangeThePaymentsFinancialStatus() {
        assertThat(Transition.RECORD_WON.ledgerEvent).isEqualTo(PaymentEventType.DISPUTE_WON);
        assertThat(Transition.RECORD_LOST.ledgerEvent).isEqualTo(PaymentEventType.DISPUTE_LOST);
        assertThat(Transition.ACCEPT_LIABILITY.ledgerEvent).isEqualTo(PaymentEventType.DISPUTE_LOST);
        assertThat(Transition.WITHDRAW.ledgerEvent).isEqualTo(PaymentEventType.DISPUTE_WITHDRAWN);
        for (Transition t : List.of(Transition.START_INVESTIGATION, Transition.REQUEST_REVIEW,
                Transition.RETURN_FOR_INVESTIGATION, Transition.APPROVE_SUBMISSION)) {
            assertThat(t.ledgerEvent).as(t.name()).isNull();
        }
    }

    @Test
    void decisionsAreSupervisorOnlyAndCarrySeparationOfDuties() {
        for (Transition t : Transition.values()) {
            boolean caseWork = t == Transition.START_INVESTIGATION || t == Transition.REQUEST_REVIEW;
            assertThat(t.performer).as(t.name()).isEqualTo(caseWork ? Performer.CARE_STAFF : Performer.SUPERVISOR);
        }
        assertThat(Transition.APPROVE_SUBMISSION.duty).isEqualTo(Duty.NOT_REVIEW_REQUESTER);
        assertThat(Transition.ACCEPT_LIABILITY.duty).isEqualTo(Duty.NOT_OPENER);
    }

    @Test
    void finalStatesAcceptNoTransition() {
        for (DisputeStatus s : EnumSet.of(DisputeStatus.WON, DisputeStatus.LOST, DisputeStatus.WITHDRAWN)) {
            assertThat(s.isFinal()).isTrue();
            DisputeCase c = caseIn(s, ALICE);
            for (Transition t : Transition.values()) {
                assertThatThrownBy(() -> c.apply(t, SAM, T)).isInstanceOf(InvalidStateTransitionException.class);
            }
        }
    }

    @Test
    void requestingReviewRecordsTheRequesterByImmutableId() {
        DisputeCase c = caseIn(DisputeStatus.UNDER_INVESTIGATION, ALICE);
        c.apply(Transition.REQUEST_REVIEW, Actor.user("sub-alice", "renamed.alice"), T);
        assertThat(c.reviewRequestedBy(ALICE)).isTrue();          // same sub, different username
        assertThat(c.reviewRequestedBy(Actor.user("sub-other", "agent.alice"))).isFalse(); // same name, other sub
    }

    @Test
    void allowedActionsFollowRoleStatusAndSeparationOfDuties() {
        DisputeCase pendingReview = caseIn(DisputeStatus.PENDING_REVIEW, SAM); // opened by Sam
        pendingReviewRequestedBy(pendingReview, SAM);

        login(SAM, "CARE_SUPERVISOR");
        assertThat(DisputeService.allowedActions(pendingReview, SAM, true)).containsExactly(DisputeAction.ADD_NOTE,
                DisputeAction.ADD_EVIDENCE, DisputeAction.RETURN_FOR_INVESTIGATION, DisputeAction.WITHDRAW);
        login(TARA, "CARE_SUPERVISOR");
        assertThat(DisputeService.allowedActions(pendingReview, TARA, true)).containsExactly(DisputeAction.ADD_NOTE,
                DisputeAction.ADD_EVIDENCE, DisputeAction.RETURN_FOR_INVESTIGATION, DisputeAction.APPROVE_SUBMISSION,
                DisputeAction.ACCEPT_LIABILITY, DisputeAction.WITHDRAW);
        login(ALICE, "CARE_AGENT");
        assertThat(DisputeService.allowedActions(pendingReview, ALICE, true))
                .containsExactly(DisputeAction.ADD_NOTE, DisputeAction.ADD_EVIDENCE);
        login(Actor.user("sub-ada", "ada.admin"), "ADMIN");
        assertThat(DisputeService.allowedActions(pendingReview, Actor.user("sub-ada", "ada.admin"), true)).isEmpty();

        login(ALICE, "CARE_AGENT");
        DisputeCase investigating = caseIn(DisputeStatus.UNDER_INVESTIGATION, ALICE);
        assertThat(DisputeService.allowedActions(investigating, ALICE, false)).doesNotContain(DisputeAction.REQUEST_REVIEW);
        assertThat(DisputeService.allowedActions(investigating, ALICE, true)).contains(DisputeAction.REQUEST_REVIEW);
        assertThat(DisputeService.allowedActions(caseIn(DisputeStatus.WON, ALICE), ALICE, true)).isEmpty();
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static DisputeCase caseIn(DisputeStatus status, Actor opener) {
        DisputeCase c = new DisputeCase(UUID.randomUUID(), DisputeService.newCaseRef(), UUID.randomUUID(), "PAY-000001",
                DisputeReasonCategory.NOT_AS_DESCRIBED, null, new BigDecimal("10.00"), "GBP", null, opener, T, null,
                "key-12345678", "fp");
        List<Transition> path = switch (status) {
            case OPEN -> List.of();
            case UNDER_INVESTIGATION -> List.of(Transition.START_INVESTIGATION);
            case PENDING_REVIEW -> List.of(Transition.START_INVESTIGATION, Transition.REQUEST_REVIEW);
            case EVIDENCE_SUBMITTED -> List.of(Transition.START_INVESTIGATION, Transition.REQUEST_REVIEW,
                    Transition.APPROVE_SUBMISSION);
            case WON -> List.of(Transition.START_INVESTIGATION, Transition.REQUEST_REVIEW,
                    Transition.APPROVE_SUBMISSION, Transition.RECORD_WON);
            case LOST -> List.of(Transition.ACCEPT_LIABILITY);
            case WITHDRAWN -> List.of(Transition.WITHDRAW);
        };
        for (Transition t : path) {
            c.apply(t, opener, T);
        }
        return c;
    }

    private static void pendingReviewRequestedBy(DisputeCase c, Actor requester) {
        assertThat(c.reviewRequestedBy(requester)).isTrue();
    }

    private static void login(Actor actor, String role) {
        var user = new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, new OidcIdToken("t", T, T.plusSeconds(60),
                Map.of("sub", actor.subject(), "preferred_username", actor.username())));
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(user, AuthorityUtils.createAuthorityList("ROLE_" + role), "keycloak"));
    }
}
