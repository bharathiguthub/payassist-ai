package com.payassist.dispute.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Append-only: save and find only. */
public interface DisputeEvidenceRepository extends Repository<DisputeEvidenceItem, UUID> {

    DisputeEvidenceItem save(DisputeEvidenceItem evidence);

    List<DisputeEvidenceItem> findByCaseIdOrderByAddedAtAsc(UUID caseId);

    long countByCaseId(UUID caseId);

    Optional<DisputeEvidenceItem> findByAddedBySubjectAndIdempotencyKey(String subject, String idempotencyKey);
}
