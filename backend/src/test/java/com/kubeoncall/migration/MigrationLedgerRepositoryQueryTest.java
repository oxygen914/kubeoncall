package com.kubeoncall.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Verifies the batch/diff query paths return paged results and fall back safely when MySQL is off. */
class MigrationLedgerRepositoryQueryTest {

    @Test
    void listBatchesReturnsPagedRows() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(3L);
        MigrationLedgerRepository.BatchSummary sample = new MigrationLedgerRepository.BatchSummary(
                "mbt_1", "active-alarm", "DRY_RUN", "COMPLETED", null, null, 10, 8, 1, 1, "alarm-active:last");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(sample));
        MigrationLedgerRepository repo = newRepo(jdbcTemplate);

        MigrationLedgerRepository.BatchPage page = repo.listBatches(1, 20, "active-alarm");

        assertThat(page.total()).isEqualTo(3L);
        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().get(0).batchId()).isEqualTo("mbt_1");
        assertThat(page.rows().get(0).migrated()).isEqualTo(8L);
    }

    @Test
    void listDiffsReturnsPagedRows() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);
        MigrationLedgerRepository.DiffSummary sample = new MigrationLedgerRepository.DiffSummary(
                "mdiff_1",
                "active-alarm",
                "alm_1",
                "FIELD_MISMATCH",
                "status=FIRING",
                "status=RESOLVED",
                "req_1",
                null);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(sample));
        MigrationLedgerRepository repo = newRepo(jdbcTemplate);

        MigrationLedgerRepository.DiffPage page = repo.listDiffs(1, 20, null);

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.rows().get(0).diffType()).isEqualTo("FIELD_MISMATCH");
        assertThat(page.rows().get(0).mysqlSummary()).isEqualTo("status=RESOLVED");
    }

    @Test
    void returnsEmptyWhenMySqlUnavailable() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        MigrationLedgerRepository repo = new MigrationLedgerRepository(provider);
        assertThat(repo.listBatches(1, 20, null).rows()).isEmpty();
        assertThat(repo.listDiffs(1, 20, null).rows()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static MigrationLedgerRepository newRepo(JdbcTemplate jdbcTemplate) {
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbcTemplate);
        return new MigrationLedgerRepository(provider);
    }
}
