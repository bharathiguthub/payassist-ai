package com.payassist;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Fails the build if a module reaches into another module's internals or modules form a cycle. */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(PayAssistApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        modules.verify();
    }
}
