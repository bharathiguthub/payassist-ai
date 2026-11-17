/**
 * Cross-cutting building blocks (error handling, paging, correlation IDs) usable by every module.
 * Declared OPEN so its sub-packages are visible to other modules; it must never depend on them.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.payassist.shared;

import org.springframework.modulith.ApplicationModule;
