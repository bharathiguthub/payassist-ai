package com.payassist.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * Like {@link IntegrationTest}, for tests that COMMIT changes to the database (state transitions,
 * concurrency tests). The extra property gives these classes a different Spring context cache key,
 * so they share their own PostgreSQL container and never disturb the read-only integration tests,
 * which assert exact synthetic seed counts. Mutating tests must not depend on each other's writes:
 * each test picks payments it alone uses.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@IntegrationTest
@TestPropertySource(properties = "payassist.test.database=mutating")
public @interface MutatingIntegrationTest {
}
