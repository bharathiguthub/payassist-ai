package com.payassist.identity.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Imports the real security configuration into {@code @WebMvcTest} slices, so slice tests exercise
 * the production filter chain and method security rather than a test double.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({SecurityConfig.class, KeycloakClientRegistrationConfig.class, KeycloakRoleMapper.class})
public class SecurityTestConfig {
}
