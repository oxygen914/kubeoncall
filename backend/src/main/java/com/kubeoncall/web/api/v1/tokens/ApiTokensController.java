package com.kubeoncall.web.api.v1.tokens;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.idempotency.IdempotencyService;
import com.kubeoncall.identity.ApiTokenRepository;
import com.kubeoncall.identity.ApiTokenRepository.ApiTokenRow;
import com.kubeoncall.identity.ApiTokenRepository.GeneratedToken;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

/**
 * {@code /api/v1/api-tokens} — API token management (WBS-4 GAP-04-01). {@code token:read-own} lists the
 * caller's tokens; {@code token:manage-all} (admin) lists every token. {@code token:manage-own} creates
 * and revokes the caller's own tokens. The plaintext token is returned exactly once on create and
 * never persisted — the idempotency replay deliberately returns a token-less view so the secret cannot
 * leak through the idempotency store.
 */
@RestController
@RequestMapping("/api/v1/api-tokens")
public class ApiTokensController {

    private static final int IDEMPOTENCY_KEY_MIN_LENGTH = 16;
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectProvider<ApiTokenRepository> tokenRepositoryProvider;
    private final OperationAuditWriter auditWriter;
    private final V1Security security;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public ApiTokensController(
            ObjectProvider<ApiTokenRepository> tokenRepositoryProvider,
            OperationAuditWriter auditWriter,
            V1Security security,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper) {
        this.tokenRepositoryProvider = tokenRepositoryProvider;
        this.auditWriter = auditWriter;
        this.security = security;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        V1Principal actor = security.requirePermission(PermissionCode.TOKEN_READ_OWN);
        ApiTokenRepository repository = requireTokenRepository();
        List<ApiTokenRow> rows = actor.hasPermission(PermissionCode.TOKEN_MANAGE_ALL)
                ? repository.listAll()
                : repository.listByOwner(actor.user().id());
        return ApiResponse.ok(rows.stream().map(ApiTokensController::toView).toList(), currentRequestId());
    }

    @PostMapping
    @Transactional
    public ApiResponse<Map<String, Object>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTokenRequest request) {
        V1Principal actor = security.requirePermission(PermissionCode.TOKEN_MANAGE_OWN);
        List<String> scopes = normalizeScopes(request.scopes(), actor);
        request = new CreateTokenRequest(request.name(), scopes, request.expiresAt());
        CommandIdempotency command = begin(
                actor,
                "POST:/api/v1/api-tokens",
                idempotencyKey,
                "create|" + request.name() + "|" + request.scopes() + "|" + request.expiresAt());
        if (command.replay() != null) {
            return command.replay();
        }
        if (request.expiresAt() == null || !request.expiresAt().isAfter(Instant.now())) {
            throw new V1ApiException(
                    HttpStatus.BAD_REQUEST.value(),
                    V1ApiErrorCode.INVALID_REQUEST,
                    "expiresAt must be a future instant");
        }
        ApiTokenRepository repository = requireTokenRepository();
        GeneratedToken generated =
                repository.create(actor.user().id(), request.name(), request.scopes(), request.expiresAt());
        Map<String, Object> persistedView = tokenView(generated, request, actor);
        // Persist the secret-free view so an idempotency replay can never surface the plaintext.
        command = command.withReplayData(persistedView);
        writeAudit(actor, "token.create", generated.publicId(), Map.of(), persistedView);
        idempotencyService.succeed(command.scope(), command.key(), 200, persistedView, "token", generated.publicId());
        // The first-time response includes the plaintext exactly once.
        Map<String, Object> responseView = new LinkedHashMap<>(persistedView);
        responseView.put("token", generated.plaintext());
        return ApiResponse.ok(responseView, currentRequestId());
    }

    @DeleteMapping("/{tokenId}")
    @Transactional
    public ApiResponse<Map<String, Object>> revoke(
            @PathVariable String tokenId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        V1Principal actor = security.requirePermission(PermissionCode.TOKEN_MANAGE_OWN);
        CommandIdempotency command = begin(
                actor, "DELETE:/api/v1/api-tokens/" + tokenId, idempotencyKey, "revoke|" + tokenId + "|" + ifMatch);
        if (command.replay() != null) {
            return command.replay();
        }
        ApiTokenRepository repository = requireTokenRepository();
        ApiTokenRow token = repository
                .findByPublicId(tokenId)
                .orElseThrow(() -> V1ApiException.notFound("API token not found: " + tokenId));
        if (token.ownerUserId() != actor.user().id() && !actor.hasPermission(PermissionCode.TOKEN_MANAGE_ALL)) {
            throw V1ApiException.forbidden("Cannot revoke another user's API token");
        }
        if (token.revoked()) {
            throw new V1ApiException(
                    HttpStatus.CONFLICT.value(), V1ApiErrorCode.CONFLICT, "API token is already revoked");
        }
        long expectedVersion = expectedVersion(ifMatch, token);
        boolean revoked = repository.revokeByPublicIdIfVersion(
                tokenId, expectedVersion, actor.user().id());
        if (!revoked) {
            throw new V1ApiException(
                    HttpStatus.CONFLICT.value(),
                    V1ApiErrorCode.RESOURCE_VERSION_CONFLICT,
                    "API token was modified by another request");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", tokenId);
        data.put("revokedAt", Instant.now().toString());
        data.put("version", expectedVersion + 1);
        writeAudit(actor, "token.revoke", tokenId, toView(token), data);
        idempotencyService.succeed(command.scope(), command.key(), 200, data, "token", tokenId);
        return ApiResponse.ok(data, currentRequestId());
    }

    private ApiTokenRepository requireTokenRepository() {
        ApiTokenRepository repository = tokenRepositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            throw new V1ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "API token repository is not available");
        }
        return repository;
    }

    private void writeAudit(
            V1Principal actor, String action, String tokenId, Map<String, Object> before, Map<String, Object> after) {
        auditWriter.write(OperationAuditWriter.builder()
                .actor("USER", actor.user().id(), actor.user().displayName())
                .action(action)
                .resource("api-token", tokenId)
                .before(before)
                .after(after)
                .requestId(currentRequestId())
                .build());
    }

    private static Map<String, Object> tokenView(
            GeneratedToken generated, CreateTokenRequest request, V1Principal actor) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", generated.publicId());
        view.put("name", request.name());
        view.put("prefix", generated.tokenPrefix());
        view.put("scopes", request.scopes());
        view.put("expiresAt", request.expiresAt().toString());
        view.put("ownerId", actor.user().publicId());
        view.put("version", 1);
        return view;
    }

    private static List<String> normalizeScopes(List<String> requested, V1Principal actor) {
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        if (requested != null) {
            requested.stream()
                    .filter(scope -> scope != null && !scope.isBlank())
                    .map(String::trim)
                    .forEach(scopes::add);
        }
        if (scopes.isEmpty()) {
            throw new V1ApiException(
                    HttpStatus.BAD_REQUEST.value(),
                    V1ApiErrorCode.INVALID_REQUEST,
                    "At least one API token scope is required");
        }
        List<String> unauthorized =
                scopes.stream().filter(scope -> !actor.hasPermission(scope)).toList();
        if (!unauthorized.isEmpty()) {
            throw V1ApiException.forbidden(
                    "API token scopes exceed the caller's permissions: " + String.join(", ", unauthorized));
        }
        return List.copyOf(scopes);
    }

    private static Map<String, Object> toView(ApiTokenRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.publicId());
        view.put("name", row.name());
        view.put("prefix", row.tokenPrefix());
        view.put("scopes", row.scopes());
        view.put("expiresAt", row.expiresAt() == null ? null : row.expiresAt().toString());
        view.put(
                "lastUsedAt", row.lastUsedAt() == null ? null : row.lastUsedAt().toString());
        view.put("revokedAt", row.revokedAt() == null ? null : row.revokedAt().toString());
        view.put("ownerId", row.ownerPublicId());
        view.put("ownerUsername", row.ownerUsername());
        view.put("version", row.version());
        view.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
        return view;
    }

    private static long expectedVersion(String ifMatch, ApiTokenRow token) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return token.version();
        }
        try {
            long version = Long.parseLong(ifMatch.replace("\"", "").trim());
            if (version <= 0) {
                throw invalidVersion();
            }
            return version;
        } catch (NumberFormatException ex) {
            throw invalidVersion();
        }
    }

    private static V1ApiException invalidVersion() {
        return new V1ApiException(
                HttpStatus.BAD_REQUEST.value(),
                V1ApiErrorCode.INVALID_REQUEST,
                "If-Match must be a positive numeric version");
    }

    private static String currentRequestId() {
        return RequestIdFilter.currentRequestId();
    }

    private CommandIdempotency begin(V1Principal actor, String route, String idempotencyKey, String canonicalRequest) {
        if (idempotencyKey == null
                || idempotencyKey.length() < IDEMPOTENCY_KEY_MIN_LENGTH
                || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new V1ApiException(
                    HttpStatus.BAD_REQUEST.value(),
                    V1ApiErrorCode.INVALID_REQUEST,
                    "Idempotency-Key must contain 16 to 128 characters");
        }
        IdempotencyService.IdempotencyScope scope =
                new IdempotencyService.IdempotencyScope("USER", actor.user().publicId(), route);
        IdempotencyService.BeginResult result = idempotencyService.begin(scope, idempotencyKey, canonicalRequest);
        return switch (result.action()) {
            case EXECUTE -> new CommandIdempotency(scope, idempotencyKey, null, null);
            case REPLAY -> new CommandIdempotency(scope, idempotencyKey, replay(result.responseJson()), null);
            case IN_PROGRESS ->
                throw new V1ApiException(
                        HttpStatus.CONFLICT.value(),
                        V1ApiErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                        "An idempotent request for this key is already in progress");
            case REUSED ->
                throw new V1ApiException(
                        HttpStatus.CONFLICT.value(),
                        V1ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key was used with a different request");
        };
    }

    private ApiResponse<Map<String, Object>> replay(String responseJson) {
        try {
            Map<String, Object> stored = objectMapper.readValue(responseJson, MAP_TYPE);
            // A replay never resurfaces the one-time plaintext.
            stored.put("tokenReplayed", true);
            return ApiResponse.ok(stored, currentRequestId());
        } catch (Exception ex) {
            throw new IllegalStateException("Stored token command response is invalid", ex);
        }
    }

    private record CommandIdempotency(
            IdempotencyService.IdempotencyScope scope,
            String key,
            ApiResponse<Map<String, Object>> replay,
            Map<String, Object> replayData) {

        CommandIdempotency withReplayData(Map<String, Object> data) {
            return new CommandIdempotency(scope, key, null, data);
        }
    }

    public record CreateTokenRequest(
            @NotBlank @Size(max = 128) String name, List<String> scopes, Instant expiresAt) {}
}
