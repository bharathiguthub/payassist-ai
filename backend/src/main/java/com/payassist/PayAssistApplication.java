package com.payassist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PayAssist AI backend.
 *
 * <p>Modular monolith: each direct sub-package of {@code com.payassist} is an application module
 * (customer, payment, audit, identity, shared). Module boundaries are verified by
 * {@code ModularityTest} using Spring Modulith. Only types in a module's top-level package are its
 * public API; sub-packages ({@code internal}, {@code web}, {@code security}) are private.
 */
@SpringBootApplication
public class PayAssistApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayAssistApplication.class, args);
    }
}
