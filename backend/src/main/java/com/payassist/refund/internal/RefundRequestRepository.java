package com.payassist.refund.internal;

import com.payassist.refund.RefundRequestStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID>,
        JpaSpecificationExecutor<RefundRequest> {

    Optional<RefundRequest> findByRefundRef(String refundRef);

    /** Payment reference only (no entity), used to take the payment lock before loading the request. */
    @Query("select r.paymentRef from RefundRequest r where r.refundRef = :refundRef")
    Optional<String> findPaymentRefByRefundRef(String refundRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundRequest r where r.refundRef = :refundRef")
    Optional<RefundRequest> findByRefundRefForUpdate(String refundRef);

    Optional<RefundRequest> findByRequestedBySubjectAndIdempotencyKey(String requestedBySubject, String idempotencyKey);

    /** Sum of amounts reserved by requests in the given (active) statuses. */
    @Query("select coalesce(sum(r.amount), 0) from RefundRequest r "
            + "where r.paymentId = :paymentId and r.status in :statuses")
    BigDecimal sumAmount(UUID paymentId, Collection<RefundRequestStatus> statuses);

    @Query("select coalesce(sum(r.amount), 0) from RefundRequest r "
            + "where r.paymentId = :paymentId and r.status in :statuses and r.id <> :excludedId")
    BigDecimal sumAmountExcluding(UUID paymentId, Collection<RefundRequestStatus> statuses, UUID excludedId);
}
