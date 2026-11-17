package com.payassist.payment.internal;

import com.payassist.payment.PaymentStatus;
import java.util.Collection;
import org.springframework.data.jpa.domain.Specification;

/** Composable search predicates. Nothing is concatenated into SQL; all values are bound parameters. */
final class PaymentSpecifications {

    private PaymentSpecifications() {
    }

    static Specification<Payment> hasStatus(PaymentStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    static Specification<Payment> paymentRefStartsWith(String prefix) {
        return (root, query, cb) -> cb.like(root.get("paymentRef"), escapeLike(prefix) + "%", '\\');
    }

    static Specification<Payment> customerIdIn(Collection<?> customerIds) {
        return (root, query, cb) -> root.get("customerId").in(customerIds);
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
