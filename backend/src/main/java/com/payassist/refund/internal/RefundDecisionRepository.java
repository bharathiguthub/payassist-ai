package com.payassist.refund.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Append-only: save and find only, no update or delete methods. */
public interface RefundDecisionRepository extends Repository<RefundDecision, UUID> {

    RefundDecision save(RefundDecision decision);

    Optional<RefundDecision> findByRefundRequestId(UUID refundRequestId);

    List<RefundDecision> findByRefundRequestIdIn(Collection<UUID> refundRequestIds);
}
