package com.payassist.identity.web;

import com.payassist.identity.Roles;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns the logged-in user's profile and roles. The SPA calls this on startup: 401 means "not
 * logged in"; 200 with an empty role list means "logged in but not authorised for PayAssist".
 * Tokens are never returned to the browser.
 */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    /**
     * Roles are read from the {@link Authentication}, not from the {@link OidcUser} principal: the
     * {@code GrantedAuthoritiesMapper} output is stored on the authentication token only, while the
     * principal keeps the provider's original authorities.
     */
    @GetMapping
    MeResponse me(Authentication authentication) {
        OidcUser user = (OidcUser) authentication.getPrincipal();
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .filter(Roles.ALL::contains)
                .distinct()
                .sorted()
                .toList();
        return new MeResponse(user.getPreferredUsername(), user.getFullName(), user.getEmail(), roles);
    }

    record MeResponse(String username, String displayName, String email, List<String> roles) {
    }
}
