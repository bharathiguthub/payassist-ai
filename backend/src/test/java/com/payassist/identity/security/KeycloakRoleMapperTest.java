package com.payassist.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

class KeycloakRoleMapperTest {

    private final KeycloakRoleMapper mapper = new KeycloakRoleMapper();

    @Test
    void mapsKnownRealmRolesToSpringRoles() {
        var authorities = mapper.mapAuthorities(List.of(oidcAuthority(List.of("CARE_AGENT", "CARE_SUPERVISOR"))));

        assertThat(names(authorities)).contains("ROLE_CARE_AGENT", "ROLE_CARE_SUPERVISOR");
    }

    @Test
    void ignoresRolesOutsideTheAllowList() {
        var authorities = mapper.mapAuthorities(
                List.of(oidcAuthority(List.of("offline_access", "default-roles-payassist", "uma_authorization", "ROLE_ADMIN"))));

        assertThat(names(authorities)).noneMatch(a -> a.startsWith("ROLE_"));
    }

    @Test
    void toleratesMissingOrMalformedRolesClaim() {
        assertThat(KeycloakRoleMapper.rolesFrom(Map.of())).isEmpty();
        assertThat(KeycloakRoleMapper.rolesFrom(Map.of("roles", "ADMIN"))).isEmpty();
        assertThat(KeycloakRoleMapper.rolesFrom(Map.of("roles", List.of(42, "ADMIN"))))
                .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    @Test
    void keepsOriginalAuthorities() {
        GrantedAuthority original = oidcAuthority(List.of("ADMIN"));

        assertThat(List.<GrantedAuthority>copyOf(mapper.mapAuthorities(List.of(original)))).contains(original);
    }

    private static OidcUserAuthority oidcAuthority(List<String> roles) {
        var idToken = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("sub", "user-1", "roles", roles));
        return new OidcUserAuthority(idToken);
    }

    private static List<String> names(java.util.Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }
}
