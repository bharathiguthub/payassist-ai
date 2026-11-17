package com.payassist.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ActorTest {

    @Test
    void sameUserIsDecidedBySubjectNotUsername() {
        Actor alice = Actor.user("sub-1", "agent.alice");
        Actor renamed = Actor.user("sub-1", "alice.renamed");
        Actor impostor = Actor.user("sub-2", "agent.alice");

        assertThat(alice.isSameUserAs(renamed)).isTrue();
        assertThat(alice.isSameUserAs(impostor)).isFalse();
        assertThat(alice.isSameUserAs(null)).isFalse();
    }

    @Test
    void systemActorsAreNeverTheSameUser() {
        Actor system = Actor.system("processor-simulator");
        assertThat(system.isSystem()).isTrue();
        assertThat(system.username()).isEqualTo("system:processor-simulator");
        assertThat(system.isSameUserAs(Actor.system("processor-simulator"))).isFalse();
    }

    @Test
    void humanActorRequiresSubject() {
        assertThatThrownBy(() -> new Actor(null, "agent.alice")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Actor.user("sub", " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
