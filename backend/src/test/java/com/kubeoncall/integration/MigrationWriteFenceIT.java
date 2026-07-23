package com.kubeoncall.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.kubeoncall.migration.MigrationLedgerRepository;

/** Verifies a successor migration owner fences stale MySQL writes, not merely its Redis lease. */
@Testcontainers
class MigrationWriteFenceIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("kubeoncall")
            .withUsername("kubeoncall")
            .withPassword("test-password")
            .withReuse(false);

    private static JdbcTemplate jdbcTemplate;
    private static MigrationLedgerRepository ledger;

    @BeforeAll
    static void setUp() {
        DataSource dataSource = DataSourceBuilder.create()
                .url(MYSQL.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false")
                .username(MYSQL.getUsername())
                .password(MYSQL.getPassword())
                .build();
        jdbcTemplate = new JdbcTemplate(dataSource);
        org.flywaydb.core.Flyway.configure()
                .dataSource(
                        MYSQL.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false",
                        MYSQL.getUsername(),
                        MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        ledger = new MigrationLedgerRepository(new SingletonObjectProvider<>(jdbcTemplate));
    }

    @Test
    void successorGenerationRejectsStaleOwnerBeforeItCanWriteMigrationFacts() {
        String domain = "fence-it-" + UUID.randomUUID();
        MigrationLedgerRepository.MigrationWriteFence first = ledger.claimWriteFence(domain, "owner-first");
        MigrationLedgerRepository.MigrationWriteFence second = ledger.claimWriteFence(domain, "owner-second");

        assertThat(second.generation()).isEqualTo(first.generation() + 1);
        assertThatThrownBy(() -> ledger.withWriteFence(first, () -> insertDiff(domain)))
                .isInstanceOf(MigrationLedgerRepository.LostMigrationWriteFenceException.class);
        assertThat(diffCount(domain)).isZero();

        ledger.withWriteFence(second, () -> insertDiff(domain));
        assertThat(diffCount(domain)).isEqualTo(1);
    }

    private void insertDiff(String domain) {
        jdbcTemplate.update("""
                INSERT INTO koc_migration_diff
                  (public_id, domain, diff_type)
                VALUES (?, ?, 'FENCE_TEST')
                """, "mdiff_" + UUID.randomUUID().toString().replace("-", ""), domain);
    }

    private long diffCount(String domain) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_migration_diff WHERE domain = ? AND diff_type = 'FENCE_TEST'",
                Long.class,
                domain);
        return count == null ? 0L : count;
    }

    private static final class SingletonObjectProvider<T> implements ObjectProvider<T> {

        private final T value;

        private SingletonObjectProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public T getObject() {
            return value;
        }
    }
}
