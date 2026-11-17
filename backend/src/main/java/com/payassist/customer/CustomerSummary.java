package com.payassist.customer;

import java.util.UUID;

/**
 * Customer data other modules may see. The email is always masked (data minimisation): care
 * executives can confirm identity ("is your email j***@example.com?") without seeing the full value.
 */
public record CustomerSummary(UUID id, String customerRef, String fullName, String maskedEmail, String countryCode) {
}
