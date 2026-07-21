package com.kubeoncall.identity;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Creates users and assigns built-in roles. Used by the bootstrap admin runner and the
 * {@code admin create} CLI. Keeps user creation in one place so password hashing, public id
 * generation and role assignment stay consistent. Idempotent on username: a repeated create for the
 * same username is a no-op rather than a duplicate.
 */
@Service
public class IdentityAdminService {

    private static final Logger log = LoggerFactory.getLogger(IdentityAdminService.class);

    private final ObjectProvider<IdentityRepository> repositoryProvider;
    private final PasswordHasher passwordHasher;

    public IdentityAdminService(ObjectProvider<IdentityRepository> repositoryProvider, PasswordHasher passwordHasher) {
        this.repositoryProvider = repositoryProvider;
        this.passwordHasher = passwordHasher;
    }

    public boolean isAvailable() {
        IdentityRepository repository = repositoryProvider.getIfAvailable();
        return repository != null && repository.isAvailable();
    }

    public CreateUserResult createAdmin(String username, String displayName, char[] password) {
        return createUser(username, displayName, null, password, BuiltInRole.ADMIN);
    }

    public CreateUserResult createUser(
            String username, String displayName, String email, char[] password, BuiltInRole role) {
        IdentityRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            return CreateUserResult.unavailable();
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank");
        }
        if (password == null || password.length < 12) {
            throw new IllegalArgumentException("Password must be at least 12 characters");
        }
        if (repository.usernameExists(username)) {
            return CreateUserResult.alreadyExists();
        }
        String hash = passwordHasher.hash(password);
        String publicId = "usr_" + UUID.randomUUID().toString().replace("-", "");
        long userId = repository.createUser(
                publicId, username.trim(), displayName == null ? username.trim() : displayName, email, hash);
        repository.assignRole(userId, role.name());
        log.info("Created user '{}' with role {}", username, role);
        return CreateUserResult.created(publicId);
    }

    public record CreateUserResult(Status status, String publicId) {

        public static CreateUserResult created(String publicId) {
            return new CreateUserResult(Status.CREATED, publicId);
        }

        public static CreateUserResult alreadyExists() {
            return new CreateUserResult(Status.ALREADY_EXISTS, null);
        }

        public static CreateUserResult unavailable() {
            return new CreateUserResult(Status.UNAVAILABLE, null);
        }

        public enum Status {
            CREATED,
            ALREADY_EXISTS,
            UNAVAILABLE
        }
    }
}
