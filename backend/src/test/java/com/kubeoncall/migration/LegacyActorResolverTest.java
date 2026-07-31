package com.kubeoncall.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.IdentityRepository;
import com.kubeoncall.identity.UserAccount;

class LegacyActorResolverTest {

    @Test
    void shouldResolveExactUsernameWithoutExplicitMapping() {
        Fixture fixture = fixture("");
        when(fixture.repository.findByUsername("oncall")).thenReturn(Optional.of(user(41, "oncall")));

        LegacyActorResolver.ResolvedActor resolved =
                fixture.resolver.resolve(" oncall ").orElseThrow();

        assertEquals(41, resolved.account().id());
        assertEquals("oncall", resolved.username());
        assertFalse(resolved.explicitMapping());
    }

    @Test
    void shouldResolveOnlyConfiguredLegacyAlias() {
        Fixture fixture = fixture("incident-commander=oncall-admin,system=migration-system");
        when(fixture.repository.findByUsername("oncall-admin")).thenReturn(Optional.of(user(42, "oncall-admin")));

        LegacyActorResolver.ResolvedActor resolved =
                fixture.resolver.resolve("incident-commander").orElseThrow();

        assertEquals("oncall-admin", resolved.username());
        assertTrue(resolved.explicitMapping());
        assertEquals("oncall-admin", fixture.resolver.mappedUsername("incident-commander"));
    }

    @Test
    void shouldRefuseActorWhenMappedUserDoesNotExist() {
        Fixture fixture = fixture("system=migration-system");
        when(fixture.repository.findByUsername("migration-system")).thenReturn(Optional.empty());

        assertTrue(fixture.resolver.resolve("system").isEmpty());
    }

    @Test
    void shouldRejectMalformedOrDuplicateMappings() {
        assertThrows(IllegalArgumentException.class, () -> LegacyActorResolver.parseMappings("missing-separator"));
        assertThrows(IllegalArgumentException.class, () -> LegacyActorResolver.parseMappings("system=one,system=two"));
    }

    private static Fixture fixture(String mappings) {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDataMigration().setLegacyActorUsernameMappings(mappings);
        IdentityRepository repository = mock(IdentityRepository.class);
        when(repository.isAvailable()).thenReturn(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<IdentityRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        return new Fixture(repository, new LegacyActorResolver(provider, properties));
    }

    private static UserAccount user(long id, String username) {
        return new UserAccount(
                id,
                "usr_" + id,
                username,
                username,
                username + "@example.test",
                "hash",
                "BCRYPT",
                1,
                "ACTIVE",
                1,
                1,
                Instant.now(),
                null,
                null,
                0,
                Set.of(),
                Set.of());
    }

    private record Fixture(IdentityRepository repository, LegacyActorResolver resolver) {}
}
