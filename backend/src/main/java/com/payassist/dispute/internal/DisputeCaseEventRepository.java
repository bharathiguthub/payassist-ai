package com.payassist.dispute.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Append-only: save and find only. */
public interface DisputeCaseEventRepository extends Repository<DisputeCaseEvent, UUID> {

    DisputeCaseEvent save(DisputeCaseEvent event);

    List<DisputeCaseEvent> findByCaseIdOrderBySequenceNoAsc(UUID caseId);

    @Query("select coalesce(max(e.sequenceNo), 0) from DisputeCaseEvent e where e.caseId = :caseId")
    int findMaxSequenceNo(UUID caseId);
}
