package com.payassist.payment.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByPaymentRef(String paymentRef);

    /**
     * Loads a payment with a row lock ({@code SELECT ... FOR UPDATE}) held until the transaction ends.
     * Every write to a payment's history goes through this, so checks such as "total refunds never
     * exceed the captured amount" and sequence numbering cannot race.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.paymentRef = :paymentRef")
    Optional<Payment> findByPaymentRefForUpdate(String paymentRef);

    @Query("select new com.payassist.payment.internal.StatusCount(p.status, count(p)) "
            + "from Payment p group by p.status order by p.status")
    List<StatusCount> countByStatus();
}
