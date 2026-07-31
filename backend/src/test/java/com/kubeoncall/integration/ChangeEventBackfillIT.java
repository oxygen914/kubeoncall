package com.kubeoncall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.correlation.ChangeEvent;
import com.kubeoncall.alarm.correlation.MysqlChangeEventRepository;
import com.kubeoncall.alarm.correlation.RedisChangeEventRepository;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.migration.ChangeEventBackfillRunner;
import com.kubeoncall.migration.MigrationLedgerRepository;

/**
 * Verifies the change-event backfill against real MySQL + Redis: a ZSCAN over the timeline ZSET
 * persists each member to {@code koc_change_event}, and a replay after checkpoint loss is idempotent
 * (no duplicate facts). Also covers the windowed MySQL query and duplicate delivery.
 */
@Testcontainers
class ChangeEventBackfillIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("kubeoncall")
            .withUsername("kubeoncall")
            .withPassword("test-password")
            .withReuse(false);

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static JdbcTemplate jdbcTemplate;
    private static ObjectMapper objectMapper;
    private LettuceConnectionFactory redisConnectionFactory;
    private StringRedisTemplate redis;
    private MysqlChangeEventRepository mysqlRepository;
    private ChangeEventBackfillRunner runner;

    @BeforeAll
    static void migrate() {
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
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM koc_change_event");
        jdbcTemplate.update("DELETE FROM koc_migration_item WHERE domain = 'change-event'");
        jdbcTemplate.update("DELETE FROM koc_migration_batch WHERE domain = 'change-event'");
        jdbcTemplate.update("DELETE FROM koc_migration_scan_checkpoint WHERE domain = 'change-event'");
        redisConnectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        redisConnectionFactory.start();
        redis = new StringRedisTemplate(redisConnectionFactory);
        redis.afterPropertiesSet();
        redis.delete(RedisChangeEventRepository.TIMELINE_KEY);

        mysqlRepository = new MysqlChangeEventRepository(jdbcTemplate, objectMapper, true);
        MigrationLedgerRepository ledger = new MigrationLedgerRepository(new SingletonObjectProvider<>(jdbcTemplate));
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDataMigration().setBackfillDryRun(false);
        properties.getDataMigration().setBackfillBatchSize(10);
        runner = new ChangeEventBackfillRunner(
                new SingletonObjectProvider<>(redis),
                new SingletonObjectProvider<>(mysqlRepository),
                new SingletonObjectProvider<>(ledger),
                new SingletonObjectProvider<>(objectMapper),
                properties);
    }

    @AfterEach
    void tearDown() {
        if (redisConnectionFactory != null) {
            redisConnectionFactory.stop();
        }
    }

    @Test
    void persistsZsetMembersAndIsIdempotentOnReplay() throws Exception {
        ChangeEvent event = event("change-it-1", "payment-api", "prod", "payments");
        seedTimeline(event);

        ChangeEventBackfillRunner.BackfillResult first = runner.run(false, "req_first");
        assertThat(first.migrated()).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(1);

        // Simulate a lost Redis cursor: drop the checkpoint and rerun. The already-migrated change id
        // must be skipped, not duplicated.
        jdbcTemplate.update("DELETE FROM koc_migration_scan_checkpoint WHERE domain = 'change-event'");
        ChangeEventBackfillRunner.BackfillResult replay = runner.run(false, "req_replay");
        assertThat(replay.migrated()).isZero();
        assertThat(replay.skipped()).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void duplicateDeliveryDoesNotProduceADuplicateFact() throws Exception {
        ChangeEvent event = event("change-it-2", "billing-api", "prod", "billing");
        seedTimeline(event);
        seedTimeline(event);

        runner.run(false, "req_dup");

        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void windowedQueryFiltersByClusterAndNamespace() throws Exception {
        ChangeEvent matching = event("change-it-3", "payment-api", "prod", "payments");
        ChangeEvent otherCluster = event("change-it-4", "payment-api", "staging", "payments");
        ChangeEvent otherNamespace = event("change-it-5", "payment-api", "prod", "infra");
        seedTimeline(matching, otherCluster, otherNamespace);

        runner.run(false, "req_window");

        List<ChangeEvent> results = mysqlRepository.findBetween(
                Instant.parse("2026-07-20T00:00:00Z"), Instant.parse("2026-07-20T03:00:00Z"), "prod", "payments");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).changeId()).isEqualTo("change-it-3");
    }

    private void seedTimeline(ChangeEvent... events) throws Exception {
        for (ChangeEvent event : events) {
            String json = objectMapper.writeValueAsString(event);
            redis.opsForZSet()
                    .add(
                            RedisChangeEventRepository.TIMELINE_KEY,
                            json,
                            event.changedAt().toEpochMilli());
        }
    }

    private long rowCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM koc_change_event", Long.class);
        return count == null ? 0 : count;
    }

    private static ChangeEvent event(String changeId, String resourceName, String cluster, String namespace) {
        return new ChangeEvent(
                changeId,
                "deployment_image_change",
                "pipeline",
                Instant.parse("2026-07-20T01:00:00Z"),
                "deployment",
                resourceName,
                namespace,
                cluster,
                Map.of("ref", "abc"),
                "github",
                changeId);
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
