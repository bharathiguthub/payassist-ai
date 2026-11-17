package com.payassist.identity.security;

import static com.payassist.identity.Roles.ADMIN;
import static com.payassist.identity.Roles.CARE_AGENT;
import static com.payassist.identity.Roles.CARE_SUPERVISOR;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Backend-for-Frontend security.
 *
 * <ul>
 *   <li><b>Login</b>: OIDC Authorization Code flow with PKCE (S256) against Keycloak, as a
 *       confidential client. Tokens stay on the server (in the HTTP session); the browser only
 *       holds an HttpOnly, SameSite=Lax session cookie.
 *   <li><b>CSRF</b>: because authentication is cookie-based, every state-changing request needs a
 *       CSRF token. The token is issued in the readable {@code XSRF-TOKEN} cookie and must be echoed
 *       in the {@code X-XSRF-TOKEN} header (or {@code _csrf} form field for the logout form).
 *   <li><b>API</b>: unauthenticated {@code /api/**} calls get 401 (not a login redirect) so the SPA
 *       can decide how to react; role checks happen here and again with {@code @PreAuthorize}.
 *   <li><b>Logout</b>: RP-initiated OIDC logout also ends the Keycloak SSO session.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(PayAssistSecurityProperties.class)
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository clientRegistrations,
            KeycloakRoleMapper roleMapper,
            PayAssistSecurityProperties properties,
            @Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName)
            throws Exception {

        String publicBaseUrl = properties.publicBaseUrl().replaceAll("/+$", "");

        // PKCE on top of the client secret: protects the code even if it leaks via logs/referrers.
        var authorizationRequestResolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrations, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        authorizationRequestResolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        var oidcLogout = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrations);
        oidcLogout.setPostLogoutRedirectUri(publicBaseUrl + "/");

        // Plain (non-XOR) handler: the SPA echoes the raw cookie value. The token is never rendered
        // into a compressed response body, so BREACH masking adds nothing here. Setting the request
        // attribute name to null loads the token on every request so the cookie is always present.
        var csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        var api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Any logged-in user may ask "who am I" so the UI can explain missing roles.
                        .requestMatchers("/api/v1/me").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole(ADMIN)
                        // Explicit even though /api/** below would match: refund endpoints get narrower
                        // rules in milestone 4, and each must be listed deliberately.
                        .requestMatchers(HttpMethod.POST, "/api/v1/refunds/eligibility-check")
                                .hasAnyRole(CARE_AGENT, CARE_SUPERVISOR, ADMIN)
                        .requestMatchers("/api/**").hasAnyRole(CARE_AGENT, CARE_SUPERVISOR, ADMIN)
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequestResolver))
                        .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(roleMapper))
                        .defaultSuccessUrl(publicBaseUrl + "/", true)
                        .failureUrl(publicBaseUrl + "/?error=login_failed"))
                .logout(logout -> logout
                        .logoutSuccessHandler(oidcLogout)
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies(sessionCookieName))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), api))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)));

        return http.build();
    }
}
