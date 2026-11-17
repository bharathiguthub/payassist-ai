package com.payassist.payment.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByPaymentRef(String paymentRef);

    @Query("select new com.payassist.payment.internal.StatusCount(p.status, count(p)) "
            + "from Payment p group by p.status order by p.status")
    List<StatusCount> countByStatus();
}
