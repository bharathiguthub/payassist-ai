package com.payassist.customer.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    private UUID id;

    @Column(name = "customer_ref", nullable = false, unique = true, length = 32)
    private String customerRef;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Customer() {
        // JPA
    }

    public Customer(UUID id, String customerRef, String fullName, String email, String countryCode, Instant createdAt) {
        this.id = id;
        this.customerRef = customerRef;
        this.fullName = fullName;
        this.email = email;
        this.countryCode = countryCode;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
