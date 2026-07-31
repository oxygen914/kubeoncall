package com.kubeoncall.alarm.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.alarm.domain.AlarmAction;
import com.kubeoncall.alarm.domain.AlarmCondition;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.common.config.KubeOnCallProperties;

class AlarmPolicyCompilerTest {

    @Test
    void shouldRenderPrometheusContractLabelsAndChecksum() {
        AlarmPolicyCompiler compiler = new AlarmPolicyCompiler(repository());

        var result = compiler.compile("v1", List.of(policy("cpu-p1", AlarmSeverity.P1, "5m")));

        assertEquals(1, result.ruleCount());
        assertEquals(64, result.checksum().length());
        assertTrue(result.content().contains("CpuP1"));
        assertTrue(result.content().contains("runbook-cpu"));
    }

    @Test
    void shouldRejectInvalidPrometheusDuration() {
        AlarmPolicyCompiler compiler = new AlarmPolicyCompiler(repository());

        assertThrows(
                IllegalStateException.class,
                () -> compiler.compile("v1", List.of(policy("cpu-p1", AlarmSeverity.P1, "tomorrow"))));
    }

    private static YamlAlarmPolicyRepository repository() {
        return new YamlAlarmPolicyRepository(new KubeOnCallProperties());
    }

    private static AlarmPolicy policy(String id, AlarmSeverity severity, String duration) {
        return new AlarmPolicy(
                id,
                "CpuP1",
                "host",
                "host.cpu",
                null,
                severity,
                new AlarmCondition("CpuP1", "host.cpu", null, ">", 70.0, duration, Map.of()),
                "node_cpu_usage_percent > 70",
                "15m",
                "cpu < 65",
                "runbook-cpu",
                "infra",
                new AlarmAction("host-resource", "oncall", false, false, List.of()),
                Map.of("team", "infra"),
                Map.of(),
                "v1");
    }
}
