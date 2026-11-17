package com.payassist.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payassist.shared.Actor;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class CurrentActorTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void oidcUserIsIdentifiedBySubjectAndShownByPreferredUsername() {
        var user = new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, new OidcIdToken("t", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60), Map.of("sub", "4f1c-uuid", "preferred_username", "sam.supervisor")));
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(user, AuthorityUtils.NO_AUTHORITIES, "keycloak"));

        Actor actor = CurrentActor.require();

        assertThat(actor.subject()).isEqualTo("4f1c-uuid");
        assertThat(actor.username()).isEqualTo("sam.supervisor");
    }

    @Test
    void missingOrAnonymousAuthenticationIsRejected() {
        assertThatThrownBy(CurrentActor::require).isInstanceOf(AuthenticationCredentialsNotFoundException.class);

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThatThrownBy(CurrentActor::require).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void usersCannotImpersonateSystemActors() {
        var user = new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, new OidcIdToken("t", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60), Map.of("sub", "x", "preferred_username", "system:processor-simulator")));
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(user, AuthorityUtils.NO_AUTHORITIES, "keycloak"));

        assertThatThrownBy(CurrentActor::require).isInstanceOf(IllegalArgumentException.class);
    }
}
