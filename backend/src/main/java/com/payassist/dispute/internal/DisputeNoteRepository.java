package com.payassist.dispute.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Append-only: save and find only. */
public interface DisputeNoteRepository extends Repository<DisputeNote, UUID> {

    DisputeNote save(DisputeNote note);

    List<DisputeNote> findByCaseIdOrderByCreatedAtAsc(UUID caseId);

    Optional<DisputeNote> findByAuthorSubjectAndIdempotencyKey(String subject, String idempotencyKey);
}
