package com.kubeoncall.alarm.readmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.state.ActiveAlarmState;

class AlarmIncidentProjectionTest {

    @Test
    void duplicateDeliveryIsDecidedBeforeAnyMutation() {
        DuplicateDeliveryJdbcTemplate jdbcTemplate = new DuplicateDeliveryJdbcTemplate();
        AlarmReadRepository repository = mock(AlarmReadRepository.class);
        when(repository.isAvailable()).thenReturn(true);
        AlarmIncidentProjection projection =
                new AlarmIncidentProjection(provider(jdbcTemplate), provider(repository), new ObjectMapper());
        NormalizedAlarmEvent event = event(AlarmSeverity.P2, AlarmStatus.FIRING);

        projection.project(
                event,
                AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "test"),
                state(AlarmSeverity.P2, AlarmStatus.FIRING));

        assertThat(jdbcTemplate.deliveryChecks).isEqualTo(1);
        assertThat(jdbcTemplate.mutations).isZero();
    }

    @Test
    void highSeverityResolutionRequiresConfirmation() {
        assertThat(AlarmIncidentProjection.projectedStatus(
                        event(AlarmSeverity.P0, AlarmStatus.RESOLVED), state(AlarmSeverity.P0, AlarmStatus.RESOLVED)))
                .isEqualTo("RECOVERY_PENDING");
        assertThat(AlarmIncidentProjection.projectedStatus(
                        event(AlarmSeverity.P1, AlarmStatus.RESOLVED), state(AlarmSeverity.P1, AlarmStatus.RESOLVED)))
                .isEqualTo("RECOVERY_PENDING");
    }

    @Test
    void lowSeverityResolutionIsFinal() {
        assertThat(AlarmIncidentProjection.projectedStatus(
                        event(AlarmSeverity.P2, AlarmStatus.RESOLVED), state(AlarmSeverity.P2, AlarmStatus.RESOLVED)))
                .isEqualTo("RESOLVED");
        assertThat(AlarmIncidentProjection.projectedStatus(
                        event(AlarmSeverity.P3, AlarmStatus.RESOLVED), state(AlarmSeverity.P3, AlarmStatus.RESOLVED)))
                .isEqualTo("RESOLVED");
    }

    @Test
    void onlyRefiringAfterFinalResolutionStartsNextCycle() {
        assertThat(AlarmIncidentProjection.startsNewCycle("RESOLVED", "FIRING")).isTrue();
        assertThat(AlarmIncidentProjection.startsNewCycle("RECOVERY_PENDING", "FIRING"))
                .isFalse();
        assertThat(AlarmIncidentProjection.startsNewCycle("FIRING", "FIRING")).isFalse();
        assertThat(AlarmIncidentProjection.startsNewCycle("RESOLVED", "RESOLVED"))
                .isFalse();
    }

    @Test
    void outOfOrderCumulativeCountsNeverDoubleCountOrRegress() {
        assertThat(AlarmIncidentProjection.mergedOccurrenceCount(2, 1, 0)).isEqualTo(2);
        assertThat(AlarmIncidentProjection.mergedOccurrenceCount(1, 3, 0)).isEqualTo(3);
        assertThat(AlarmIncidentProjection.mergedOccurrenceCount(2, 5, 3)).isEqualTo(2);
    }

    @Test
    void firingPreservesAcknowledgedAndSuppressedButReopensRecoveryPending() {
        assertThat(AlarmIncidentProjection.transitionStatus("ACKNOWLEDGED", "FIRING"))
                .isEqualTo("ACKNOWLEDGED");
        assertThat(AlarmIncidentProjection.transitionStatus("SUPPRESSED", "FIRING"))
                .isEqualTo("SUPPRESSED");
        assertThat(AlarmIncidentProjection.transitionStatus("RECOVERY_PENDING", "FIRING"))
                .isEqualTo("FIRING");
        assertThat(AlarmIncidentProjection.transitionStatus("FIRING", "FIRING")).isEqualTo("FIRING");
    }

    @Test
    void statusHistoryIsWrittenOnlyForCreationOrRealTransition() {
        assertThat(AlarmIncidentProjection.shouldRecordStatusHistory(null, "FIRING"))
                .isTrue();
        assertThat(AlarmIncidentProjection.shouldRecordStatusHistory("RECOVERY_PENDING", "FIRING"))
                .isTrue();
        assertThat(AlarmIncidentProjection.shouldRecordStatusHistory("FIRING", "FIRING"))
                .isFalse();
        assertThat(AlarmIncidentProjection.shouldRecordStatusHistory("ACKNOWLEDGED", "ACKNOWLEDGED"))
                .isFalse();
        assertThat(AlarmIncidentProjection.shouldRecordStatusHistory("SUPPRESSED", "SUPPRESSED"))
                .isFalse();
    }

    private static NormalizedAlarmEvent event(AlarmSeverity severity, AlarmStatus status) {
        return new NormalizedAlarmEvent(
                "delivery-1",
                "fingerprint-1",
                "ProjectionTestAlarm",
                "alertmanager",
                severity.name(),
                severity,
                AlarmResourceType.NODE,
                "worker-1",
                "prod",
                null,
                null,
                "test_metric",
                90.0,
                80.0,
                "%",
                "5m",
                Map.of(),
                Map.of(),
                null,
                status,
                Instant.parse("2026-07-20T00:00:00Z"),
                "projection test",
                Map.of());
    }

    private static ActiveAlarmState state(AlarmSeverity severity, AlarmStatus status) {
        Instant observedAt = Instant.parse("2026-07-20T00:00:00Z");
        return new ActiveAlarmState(
                "fingerprint-1",
                "delivery-1",
                "ProjectionTestAlarm",
                "prod",
                null,
                null,
                "worker-1",
                severity,
                status,
                "policy-test",
                observedAt,
                observedAt,
                1);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static final class DuplicateDeliveryJdbcTemplate extends JdbcTemplate {

        private int deliveryChecks;
        private int mutations;

        @Override
        public DataSource getDataSource() {
            return null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.contains("koc_alarm_event") && sql.contains("delivery_id")) {
                deliveryChecks++;
                return (T) Long.valueOf(1);
            }
            return fail("Unexpected query before duplicate-delivery decision: " + sql);
        }

        @Override
        public int update(String sql, Object... args) {
            mutations++;
            return fail("Duplicate delivery must not mutate the read model: " + sql);
        }
    }
}
