package com.payassist.dispute.internal;

import com.payassist.dispute.DisputeStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DisputeCaseRepository extends JpaRepository<DisputeCase, UUID>, JpaSpecificationExecutor<DisputeCase> {

    Optional<DisputeCase> findByCaseRef(String caseRef);

    /** Payment reference only, used to take the payment lock before the case lock. */
    @Query("select c.paymentRef from DisputeCase c where c.caseRef = :caseRef")
    Optional<String> findPaymentRefByCaseRef(String caseRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from DisputeCase c where c.caseRef = :caseRef")
    Optional<DisputeCase> findByCaseRefForUpdate(String caseRef);

    Optional<DisputeCase> findByOpenedBySubjectAndIdempotencyKey(String subject, String idempotencyKey);

    Optional<DisputeCase> findFirstByPaymentIdAndStatusIn(UUID paymentId, Collection<DisputeStatus> statuses);

    Optional<DisputeCase> findFirstByPaymentRefAndStatusIn(String paymentRef, Collection<DisputeStatus> statuses);
}
