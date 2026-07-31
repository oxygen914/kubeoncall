package com.kubeoncall.web.api.v1.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.idempotency.IdempotencyService;
import com.kubeoncall.identity.ApiTokenRepository;
import com.kubeoncall.identity.ApiTokenRepository.ApiTokenRow;
import com.kubeoncall.identity.ApiTokenRepository.GeneratedToken;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

/** Verifies the API token command path: list own/all, create (one-time plaintext), revoke CAS. */
class ApiTokensControllerTest {

    private static final String IDEMPOTENCY_KEY = "token-command-key-123";
    private static final Instant FUTURE = Instant.now().plusSeconds(3600);

    private ApiTokenRepository repository;
    private OperationAuditWriter auditWriter;
    private V1Security security;
    private IdempotencyService idempotencyService;
    private ApiTokensController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(ApiTokenRepository.class);
        when(repository.isAvailable()).thenReturn(true);
        auditWriter = mock(OperationAuditWriter.class);
        security = mock(V1Security.class);
        idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.begin(any(), anyString(), anyString()))
                .thenReturn(IdempotencyService.BeginResult.execute());
        ObjectProvider<ApiTokenRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        controller = new ApiTokensController(provider, auditWriter, security, idempotencyService, new ObjectMapper());
    }

    @Test
    void listReturnsOwnTokensForOperator() {
        when(security.requirePermission(PermissionCode.TOKEN_READ_OWN)).thenReturn(operator());
        when(repository.listByOwner(1L)).thenReturn(List.of(row("tok_1", 1L, "usr_1", "operator")));

        var data = controller.list().data();

        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("id")).isEqualTo("tok_1");
        verify(repository, never()).listAll();
    }

    @Test
    void listReturnsAllTokensForAdmin() {
        V1Principal admin = admin();
        when(security.requirePermission(PermissionCode.TOKEN_READ_OWN)).thenReturn(admin);
        when(repository.listAll()).thenReturn(List.of(row("tok_1", 2L, "usr_2", "alice")));

        var data = controller.list().data();

        assertThat(data).hasSize(1);
        verify(repository, never()).listByOwner(anyLong());
    }

    @Test
    void createReturnsPlaintextOnceAndPersistsSecretFreeView() {
        when(security.requirePermission(PermissionCode.TOKEN_MANAGE_OWN)).thenReturn(operator());
        when(repository.create(eq(1L), eq("ci-token"), any(), eq(FUTURE)))
                .thenReturn(new GeneratedToken("tok_1", "koc_secret_plaintext_value", "koc_secret_"));

        var data = controller
                .create(
                        IDEMPOTENCY_KEY,
                        new ApiTokensController.CreateTokenRequest("ci-token", List.of("alarm:read"), FUTURE))
                .data();

        // The first-time response carries the plaintext.
        assertThat(data.get("token")).isEqualTo("koc_secret_plaintext_value");
        // But the persisted idempotency view must not carry it.
        verify(idempotencyService)
                .succeed(
                        any(),
                        eq(IDEMPOTENCY_KEY),
                        eq(200),
                        org.mockito.ArgumentMatchers.argThat(argThatWithoutSecret()),
                        eq("token"),
                        eq("tok_1"));
    }

    @Test
    void createReplayNeverResurfacesPlaintext() {
        when(security.requirePermission(PermissionCode.TOKEN_MANAGE_OWN)).thenReturn(operator());
        when(idempotencyService.begin(any(), eq(IDEMPOTENCY_KEY), anyString()))
                .thenReturn(IdempotencyService.BeginResult.replay(
                        200, "{\"id\":\"tok_1\",\"name\":\"ci-token\",\"prefix\":\"koc_abcd1234\"}"));

        var data = controller
                .create(
                        IDEMPOTENCY_KEY,
                        new ApiTokensController.CreateTokenRequest("ci-token", List.of("alarm:read"), FUTURE))
                .data();

        assertThat(data).doesNotContainKey("token");
        assertThat(data.get("tokenReplayed")).isEqualTo(true);
        verify(repository, never()).create(anyLong(), anyString(), any(), any());
    }

    @Test
    void revokeAppliesCompareAndSetByPublicId() {
        when(security.requirePermission(PermissionCode.TOKEN_MANAGE_OWN)).thenReturn(operator());
        when(repository.findByPublicId("tok_1"))
                .thenReturn(java.util.Optional.of(row("tok_1", 1L, "usr_1", "operator")));
        when(repository.revokeByPublicIdIfVersion("tok_1", 3L, 1L)).thenReturn(true);

        var data = controller.revoke("tok_1", "3", IDEMPOTENCY_KEY).data();

        assertThat(data.get("id")).isEqualTo("tok_1");
        assertThat(data.get("version")).isEqualTo(4L);
        verify(auditWriter).write(any());
    }

    @Test
    void revokeRejectsAnotherOwnersTokenWithoutManageAll() {
        when(security.requirePermission(PermissionCode.TOKEN_MANAGE_OWN)).thenReturn(operator());
        when(repository.findByPublicId("tok_2")).thenReturn(java.util.Optional.of(row("tok_2", 9L, "usr_9", "alice")));

        assertThatThrownBy(() -> controller.revoke("tok_2", "1", IDEMPOTENCY_KEY))
                .isInstanceOf(V1ApiException.class);
        verify(repository, never()).revokeByPublicIdIfVersion(anyString(), anyLong(), anyLong());
    }

    @Test
    void adminCanRevokeAnotherOwnersToken() {
        when(security.requirePermission(PermissionCode.TOKEN_MANAGE_OWN)).thenReturn(admin());
        when(repository.findByPublicId("tok_2")).thenReturn(java.util.Optional.of(row("tok_2", 9L, "usr_9", "alice")));
        when(repository.revokeByPublicIdIfVersion("tok_2", 1L, 1L)).thenReturn(true);

        assertThat(controller.revoke("tok_2", "1", IDEMPOTENCY_KEY).data().get("id"))
                .isEqualTo("tok_2");
    }

    @Test
    void revokeConflictsOnAlreadyRevokedToken() {
        when(security.requirePermission(PermissionCode.TOKEN_MANAGE_OWN)).thenReturn(operator());
        ApiTokenRow revoked = new ApiTokenRow(
                "tok_1",
                1L,
                "usr_1",
                "operator",
                "ci",
                "koc_abcd",
                List.of(),
                FUTURE,
                null,
                Instant.now(),
                3L,
                Instant.now());
        when(repository.findByPublicId("tok_1")).thenReturn(java.util.Optional.of(revoked));

        assertThatThrownBy(() -> controller.revoke("tok_1", "3", IDEMPOTENCY_KEY))
                .isInstanceOf(V1ApiException.class);
        verify(repository, never()).revokeByPublicIdIfVersion(anyString(), anyLong(), anyLong());
    }

    @Test
    void createRejectsPastExpiry() {
        when(security.requirePermission(PermissionCode.TOKEN_MANAGE_OWN)).thenReturn(operator());
        assertThatThrownBy(() -> controller.create(
                        IDEMPOTENCY_KEY,
                        new ApiTokensController.CreateTokenRequest(
                                "ci", List.of(), Instant.now().minusSeconds(60))))
                .isInstanceOf(V1ApiException.class);
        verify(repository, never()).create(anyLong(), anyString(), any(), any());
    }

    private static org.mockito.ArgumentMatcher<java.util.Map<String, Object>> argThatWithoutSecret() {
        return map -> map != null && !map.containsKey("token");
    }

    private static V1Principal operator() {
        return principal(
                Set.of(PermissionCode.TOKEN_READ_OWN, PermissionCode.TOKEN_MANAGE_OWN, PermissionCode.ALARM_READ));
    }

    private static V1Principal admin() {
        return principal(Set.of(
                PermissionCode.TOKEN_READ_OWN, PermissionCode.TOKEN_MANAGE_OWN, PermissionCode.TOKEN_MANAGE_ALL));
    }

    private static V1Principal principal(Set<String> permissions) {
        UserAccount user = new UserAccount(
                1L,
                "usr_1",
                "operator",
                "Operator",
                null,
                "",
                "BCRYPT",
                1L,
                "ACTIVE",
                1L,
                null,
                null,
                null,
                0,
                Set.of("OPERATOR"),
                permissions);
        return new V1Principal(user, permissions, V1Principal.AuthMethod.SESSION);
    }

    private static ApiTokenRow row(String publicId, long ownerId, String ownerPublicId, String ownerUsername) {
        return new ApiTokenRow(
                publicId,
                ownerId,
                ownerPublicId,
                ownerUsername,
                "ci",
                "koc_abcd",
                List.of("alarm:read"),
                FUTURE,
                null,
                null,
                3L,
                Instant.now());
    }
}
