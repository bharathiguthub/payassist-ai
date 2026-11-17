package com.payassist.identity.security;

import com.payassist.identity.Roles;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.stereotype.Component;

/**
 * Maps Keycloak realm roles to Spring Security authorities.
 *
 * <p>The realm adds a {@code roles} claim (realm roles) to the ID token via a protocol mapper. Only
 * roles in {@link Roles#ALL} are mapped (allow-list), so Keycloak defaults such as
 * {@code offline_access} or {@code default-roles-payassist} never grant anything in the app.
 */
@Component
public class KeycloakRoleMapper implements GrantedAuthoritiesMapper {

    static final String ROLES_CLAIM = "roles";

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(Collection<? extends GrantedAuthority> authorities) {
        Set<GrantedAuthority> mapped = new LinkedHashSet<>(authorities);
        for (GrantedAuthority authority : authorities) {
            if (authority instanceof OidcUserAuthority oidc) {
                mapped.addAll(rolesFrom(oidc.getIdToken().getClaims()));
                if (oidc.getUserInfo() != null) {
                    mapped.addAll(rolesFrom(oidc.getUserInfo().getClaims()));
                }
            }
        }
        return mapped;
    }

    static Set<GrantedAuthority> rolesFrom(Map<String, Object> claims) {
        Set<GrantedAuthority> result = new LinkedHashSet<>();
        if (claims.get(ROLES_CLAIM) instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String name && Roles.ALL.contains(name)) {
                    result.add(new SimpleGrantedAuthority("ROLE_" + name));
                }
            }
        }
        return result;
    }
}
