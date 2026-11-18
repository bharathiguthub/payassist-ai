package com.payassist.shared;

/**
 * Published when the database itself refused a write (ADR 0013 section 11): a guard trigger
 * ({@code RECORD_PROTECTED}) or a missing privilege of the runtime role ({@code DATABASE_PERMISSION_DENIED}).
 * Normal flows never reach these layers, so a refusal signals a bug or an attack and is audited. Carries no database
 * error text: only the stable code and the HTTP method and path.
 */
public record DataProtectionRefusal(String code, String method, String path) {
}
