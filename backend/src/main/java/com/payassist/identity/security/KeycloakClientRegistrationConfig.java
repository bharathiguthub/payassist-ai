package com.payassist.identity.security;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Builds the Keycloak client registration explicitly instead of using OIDC discovery.
 *
 * <p>Why: in Docker the browser reaches Keycloak at {@code localhost:8180} while the backend
 * reaches it at {@code keycloak:8080}. Discovery would hand the backend browser-facing URLs it
 * cannot reach (or vice versa). Splitting front-channel (authorization, logout) and back-channel
 * (token, JWKS) endpoints while pinning the expected issuer keeps ID token validation strict and
 * also lets the app start even if Keycloak is still booting.
 */
@Configuration(proxyBeanMethods = false)
class KeycloakClientRegistrationConfig {

    static final String REGISTRATION_ID = "keycloak";
    private static final String OIDC = "/protocol/openid-connect";

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(PayAssistSecurityProperties properties) {
        return new InMemoryClientRegistrationRepository(keycloakRegistration(properties));
    }

    static ClientRegistration keycloakRegistration(PayAssistSecurityProperties properties) {
        PayAssistSecurityProperties.Keycloak kc = properties.keycloak();
        String issuer = stripTrailingSlash(kc.issuerUri());
        String backchannel = stripTrailingSlash(kc.effectiveBackchannelUri());
        String publicBaseUrl = stripTrailingSlash(properties.publicBaseUrl());

        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientName("Keycloak")
                .clientId(kc.clientId())
                .clientSecret(kc.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(publicBaseUrl + "/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                // Front channel: browser-facing
                .authorizationUri(issuer + OIDC + "/auth")
                // Back channel: server-to-server
                .tokenUri(backchannel + OIDC + "/token")
                .jwkSetUri(backchannel + OIDC + "/certs")
                // ID token 'iss' must match the browser-facing issuer exactly
                .issuerUri(issuer)
                .userNameAttributeName("preferred_username")
                .providerConfigurationMetadata(Map.of("end_session_endpoint", issuer + OIDC + "/logout"))
                .build();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
