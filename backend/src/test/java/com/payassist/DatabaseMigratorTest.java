package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseMigratorTest {

    @Test
    void passwordsAreQuotedAsSqlLiterals() {
        assertThat(DatabaseMigrator.literal("abc")).isEqualTo("'abc'");
        assertThat(DatabaseMigrator.literal("a'b; DROP ROLE x; --")).isEqualTo("'a''b; DROP ROLE x; --'");
        assertThatThrownBy(() -> DatabaseMigrator.literal("a\0b")).isInstanceOf(IllegalArgumentException.class);
    }
}
