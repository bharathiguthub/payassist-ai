package com.payassist.customer.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    @Query("select c.id from Customer c where c.customerRef like concat(:prefix, '%') order by c.customerRef")
    List<UUID> findIdsByCustomerRefPrefix(@Param("prefix") String prefix, Pageable pageable);
}
