package com.payassist.customer;

import com.payassist.customer.internal.Customer;
import com.payassist.customer.internal.CustomerRepository;
import com.payassist.customer.internal.EmailMasker;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API of the customer module. Other modules reference customers by ID only and resolve
 * display data through this service, never through the customer tables directly.
 */
@Service
@Transactional(readOnly = true)
public class CustomerDirectory {

    private final CustomerRepository customers;

    CustomerDirectory(CustomerRepository customers) {
        this.customers = customers;
    }

    public Optional<CustomerSummary> findSummary(UUID customerId) {
        return customers.findById(customerId).map(CustomerDirectory::toSummary);
    }

    public Map<UUID, CustomerSummary> findSummaries(Collection<UUID> customerIds) {
        return customers.findAllById(customerIds).stream()
                .map(CustomerDirectory::toSummary)
                .collect(Collectors.toMap(CustomerSummary::id, Function.identity()));
    }

    /** IDs of customers whose reference starts with {@code prefix} (case-insensitive), capped at {@code limit}. */
    public List<UUID> findIdsByRefPrefix(String prefix, int limit) {
        return customers.findIdsByCustomerRefPrefix(prefix.toUpperCase(), PageRequest.of(0, limit));
    }

    private static CustomerSummary toSummary(Customer c) {
        return new CustomerSummary(c.getId(), c.getCustomerRef(), c.getFullName(),
                EmailMasker.mask(c.getEmail()), c.getCountryCode());
    }
}
