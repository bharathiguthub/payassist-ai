package com.payassist.shared;

import java.util.Objects;

/**
 * Who performed an action.
 *
 * <p>{@code subject} is the identity provider's immutable user ID (the OIDC {@code sub} claim) and is
 * what business rules such as "no self-approval" compare, because a username can be renamed.
 * {@code username} is kept for display and audit readability. System actors (processor feeds,
 * migrations) have no subject and a {@code system:} username prefix.
 */
public record Actor(String subject, String username) {

    public static final String SYSTEM_PREFIX = "system:";
    private static final int MAX_LENGTH = 128;

    public Actor {
        Objects.requireNonNull(username, "username");
        if (username.isBlank() || username.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("username must be 1-" + MAX_LENGTH + " characters");
        }
        if (subject != null && (subject.isBlank() || subject.length() > MAX_LENGTH)) {
            throw new IllegalArgumentException("subject must be 1-" + MAX_LENGTH + " characters");
        }
        if (subject == null && !username.startsWith(SYSTEM_PREFIX)) {
            throw new IllegalArgumentException("a human actor needs a subject");
        }
    }

    public static Actor user(String subject, String username) {
        return new Actor(Objects.requireNonNull(subject, "subject"), username);
    }

    public static Actor system(String name) {
        return new Actor(null, SYSTEM_PREFIX + name);
    }

    public boolean isSystem() {
        return subject == null;
    }

    /** True when both actors are the same human user (compared by immutable subject). */
    public boolean isSameUserAs(Actor other) {
        return other != null && subject != null && subject.equals(other.subject);
    }
}
