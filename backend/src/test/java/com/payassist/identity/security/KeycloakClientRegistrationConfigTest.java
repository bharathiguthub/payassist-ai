package com.payassist.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

class KeycloakClientRegistrationConfigTest {

    @Test
    void splitsFrontChannelAndBackChannelEndpoints() {
        var properties = new PayAssistSecurityProperties("http://localhost:3000/",
                new PayAssistSecurityProperties.Keycloak("http://localhost:8180/realms/payassist/",
                        "http://keycloak:8080/realms/payassist", "payassist-bff", "secret"));

        var registration = KeycloakClientRegistrationConfig.keycloakRegistration(properties);
        var provider = registration.getProviderDetails();

        assertThat(registration.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(registration.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(registration.getScopes()).contains("openid");
        assertThat(registration.getRedirectUri()).isEqualTo("http://localhost:3000/login/oauth2/code/{registrationId}");
        assertThat(provider.getIssuerUri()).isEqualTo("http://localhost:8180/realms/payassist");
        assertThat(provider.getAuthorizationUri()).startsWith("http://localhost:8180/");
        assertThat(provider.getTokenUri()).startsWith("http://keycloak:8080/");
        assertThat(provider.getJwkSetUri()).startsWith("http://keycloak:8080/");
        assertThat(provider.getConfigurationMetadata().get("end_session_endpoint"))
                .isEqualTo("http://localhost:8180/realms/payassist/protocol/openid-connect/logout");
    }

    @Test
    void backchannelDefaultsToIssuer() {
        var properties = new PayAssistSecurityProperties("http://localhost:5173",
                new PayAssistSecurityProperties.Keycloak("http://localhost:8180/realms/payassist", " ",
                        "payassist-bff", "secret"));

        var registration = KeycloakClientRegistrationConfig.keycloakRegistration(properties);

        assertThat(registration.getProviderDetails().getTokenUri())
                .isEqualTo("http://localhost:8180/realms/payassist/protocol/openid-connect/token");
    }
}
