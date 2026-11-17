package com.payassist.identity.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Security settings. All values come from environment variables (see {@code .env.example}); none
 * have defaults, so the application refuses to start if a secret is missing instead of silently
 * falling back to an insecure value.
 *
 * @param publicBaseUrl the origin users reach the app on (SPA + BFF share it), e.g.
 *     {@code http://localhost:3000}. Used for the OIDC redirect URI and post-login/logout redirects.
 * @param keycloak Keycloak (OIDC provider) settings
 */
@Validated
@ConfigurationProperties(prefix = "payassist.security")
public record PayAssistSecurityProperties(@NotBlank String publicBaseUrl, @Valid @NotNull Keycloak keycloak) {

    /**
     * @param issuerUri the realm issuer as the browser sees it, e.g.
     *     {@code http://localhost:8180/realms/payassist}. Must equal the {@code iss} claim.
     * @param backchannelUri the realm URL as the backend reaches it server-to-server (inside Docker
     *     this is {@code http://keycloak:8080/realms/payassist}). Defaults to {@code issuerUri}.
     * @param clientId confidential client ID
     * @param clientSecret confidential client secret (never committed)
     */
    public record Keycloak(
            @NotBlank String issuerUri,
            String backchannelUri,
            @NotBlank String clientId,
            @NotBlank String clientSecret) {

        public String effectiveBackchannelUri() {
            return (backchannelUri == null || backchannelUri.isBlank()) ? issuerUri : backchannelUri;
        }
    }
}
