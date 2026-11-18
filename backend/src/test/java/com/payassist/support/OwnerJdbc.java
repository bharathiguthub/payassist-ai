package com.payassist.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-only connection as the <b>schema owner</b> (the container superuser), for asserting the second protection layer:
 * the append-only triggers that stop even the owner. The application itself connects as the runtime role, which is
 * already refused by missing privileges (see {@code RuntimeRolePrivilegesIT}). Never use it to set up test data.
 */
public record OwnerJdbc(JdbcTemplate template) {
}
