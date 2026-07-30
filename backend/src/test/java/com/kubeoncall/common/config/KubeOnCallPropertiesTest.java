package com.kubeoncall.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.kubeoncall.sandbox.domain.SandboxRunMode;

class KubeOnCallPropertiesTest {

    @Test
    void shouldBindExistingKubeOnCallKeysAcrossSplitConfigurationDomains() {
        Map<String, Object> source = Map.ofEntries(
                Map.entry("kubeoncall.agent.max-loops", "7"),
                Map.entry("kubeoncall.rag.embedding-dimensions", "768"),
                Map.entry("kubeoncall.storage.minio.bucket", "knowledge-test"),
                Map.entry("kubeoncall.alarm.p0-escalation-count", "5"),
                Map.entry("kubeoncall.memory.session-ttl-seconds", "120"),
                Map.entry("kubeoncall.cors.allowed-origins", "https://console.example.com"),
                Map.entry("kubeoncall.integrations.prometheus.endpoint", "http://prometheus:9090"));
        KubeOnCallProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("kubeoncall", Bindable.of(KubeOnCallProperties.class))
                .orElseThrow(() -> new AssertionError("kubeoncall properties should bind"));

        assertEquals(7, properties.getAgent().getMaxLoops());
        assertEquals(768, properties.getRag().getEmbeddingDimensions());
        assertEquals("knowledge-test", properties.getStorage().getMinio().getBucket());
        assertEquals(5, properties.getAlarm().getP0EscalationCount());
        assertEquals(120, properties.getMemory().getSessionTtlSeconds());
        assertEquals(
                List.of("https://console.example.com"), properties.getCors().getAllowedOrigins());
        assertEquals(
                "http://prometheus:9090",
                properties.getIntegrations().getPrometheus().getEndpoint());
    }

    @Test
    void shouldRetainConfigurationDefaults() {
        KubeOnCallProperties properties = new KubeOnCallProperties();

        assertTrue(properties.getAgent().isPlannerLlmEnabled());
        assertTrue(properties.getAgent().isPostExecutionVerificationEnabled());
        assertEquals(120, properties.getAgent().getPostExecutionVerificationTimeoutSeconds());
        assertEquals(5000, properties.getAgent().getPostExecutionVerificationPollMillis());
        assertTrue(properties.getAgent().isAutomaticRollbackEnabled());
        assertTrue(properties.getAgent().isPostExecutionEscalationEnabled());
        assertFalse(properties.getRag().isEmbeddingEnabled());
        assertEquals(168, properties.getAudit().getRetentionHours());
        assertEquals(3000, properties.getWorkflow().getNodeTimeoutMillis());
        assertEquals(45000, properties.getWorkflow().getDiagnosisNodeTimeoutMillis());
        assertEquals("Asia/Shanghai", properties.getMemory().getTemporalNormalizationZone());
        assertEquals(
                List.of("http://127.0.0.1:8081", "http://localhost:8081"),
                properties.getCors().getAllowedOrigins());
    }

    @Test
    void sandboxShouldDefaultToFullyDisabledWithoutChangingExistingBehavior() {
        KubeOnCallProperties.Sandbox sandbox = new KubeOnCallProperties().getSandbox();

        assertFalse(sandbox.isEnabled(), "master switch defaults to off");
        assertFalse(sandbox.isFixedDiagnostic());
        assertFalse(sandbox.isGeneratedCode());
        assertFalse(sandbox.isManifestValidation());
        assertFalse(sandbox.isRemediationSimulation());
        assertFalse(sandbox.isAgentAutoRouteEnabled());
        // Default ceilings mirror the refactor plan defaults (5/15 min timeout, 1 core, 1Gi mem...).
        assertEquals(300, sandbox.getTimeoutSeconds());
        assertEquals(900, sandbox.getMaxTimeoutSeconds());
        assertEquals("1", sandbox.getCpu());
        assertEquals("1Gi", sandbox.getMemory());
        assertEquals("2Gi", sandbox.getEphemeralStorage());
        assertEquals(50L * 1024 * 1024, sandbox.getInputMaxBytes());
        assertEquals(10L * 1024 * 1024, sandbox.getOutputMaxBytes());
        assertEquals(2L * 1024 * 1024, sandbox.getLogMaxBytes());
        assertEquals(256L * 1024, sandbox.getScriptMaxBytes());
        assertEquals(24, sandbox.getArtifactRetentionHours());
        assertEquals(1, sandbox.getPerAlarmConcurrency());
        assertEquals(4, sandbox.getGlobalConcurrency());
        // No mode is reachable while the master switch is off, regardless of per-mode toggles.
        for (SandboxRunMode mode : SandboxRunMode.values()) {
            assertFalse(sandbox.isModeEnabled(mode));
        }
    }

    @Test
    void sandboxShouldBindOperatorOverridesAndEnforcePerModeGate() {
        Map<String, Object> source = Map.ofEntries(
                Map.entry("kubeoncall.sandbox.enabled", "true"),
                Map.entry("kubeoncall.sandbox.fixed-diagnostic", "true"),
                Map.entry("kubeoncall.sandbox.generated-code", "true"),
                Map.entry("kubeoncall.sandbox.manifest-validation", "false"),
                Map.entry("kubeoncall.sandbox.remediation-simulation", "false"),
                Map.entry("kubeoncall.sandbox.agent-auto-route-enabled", "true"),
                Map.entry("kubeoncall.sandbox.timeout-seconds", "120"),
                Map.entry("kubeoncall.sandbox.max-timeout-seconds", "600"),
                Map.entry("kubeoncall.sandbox.controller-endpoint", "http://controller:8090/internal/v1"));
        KubeOnCallProperties.Sandbox sandbox = new Binder(new MapConfigurationPropertySource(source))
                .bind("kubeoncall", Bindable.of(KubeOnCallProperties.class))
                .orElseThrow(() -> new AssertionError("kubeoncall properties should bind"))
                .getSandbox();

        assertTrue(sandbox.isEnabled());
        assertTrue(sandbox.isAgentAutoRouteEnabled());
        assertEquals(120, sandbox.getTimeoutSeconds());
        assertEquals(600, sandbox.getMaxTimeoutSeconds());
        assertEquals("http://controller:8090/internal/v1", sandbox.getControllerEndpoint());
        assertTrue(sandbox.isModeEnabled(SandboxRunMode.FIXED_DIAGNOSTIC));
        assertTrue(sandbox.isModeEnabled(SandboxRunMode.GENERATED_CODE));
        assertFalse(sandbox.isModeEnabled(SandboxRunMode.MANIFEST_VALIDATION));
        assertFalse(sandbox.isModeEnabled(SandboxRunMode.REMEDIATION_SIMULATION));
    }

    @Test
    void sandboxValidateShouldRejectLimitsExceedingHardCeilings() {
        KubeOnCallProperties.Sandbox sandbox = new KubeOnCallProperties().getSandbox();
        // timeoutSeconds above its own max-timeout cap, and max-timeout itself inverted.
        sandbox.setMaxTimeoutSeconds(300);
        sandbox.setTimeoutSeconds(600);

        IllegalStateException ex = assertThrows(IllegalStateException.class, sandbox::validate);
        String message = ex.getMessage();
        assertTrue(message.contains("timeoutSeconds"), message);
        assertTrue(message.contains("maxTimeoutSeconds"), message);
    }

    @Test
    void sandboxValidateShouldRejectMaxTimeoutAboveHardCeiling() {
        KubeOnCallProperties.Sandbox sandbox = new KubeOnCallProperties().getSandbox();
        sandbox.setMaxTimeoutSeconds(1800);

        IllegalStateException ex = assertThrows(IllegalStateException.class, sandbox::validate);
        assertTrue(ex.getMessage().contains("maxTimeoutSeconds"), ex.getMessage());
    }

    @Test
    void sandboxValidateShouldRejectOutputAndScriptAndConcurrencyOverruns() {
        KubeOnCallProperties.Sandbox sandbox = new KubeOnCallProperties().getSandbox();
        sandbox.setOutputMaxBytes(Long.MAX_VALUE);
        sandbox.setScriptMaxBytes(Long.MAX_VALUE);
        sandbox.setPerAlarmConcurrency(10);
        sandbox.setGlobalConcurrency(99);
        sandbox.setArtifactRetentionHours(72);

        IllegalStateException ex = assertThrows(IllegalStateException.class, sandbox::validate);
        String message = ex.getMessage();
        assertTrue(message.contains("outputMaxBytes"), message);
        assertTrue(message.contains("scriptMaxBytes"), message);
        assertTrue(message.contains("perAlarmConcurrency"), message);
        assertTrue(message.contains("globalConcurrency"), message);
        assertTrue(message.contains("artifactRetentionHours"), message);
    }

    @Test
    void sandboxValidateShouldAcceptDefaultsWithinCeilings() {
        // The shipped defaults must pass validation so an enabled deployment boots cleanly.
        assertDoesNotThrowValidate(new KubeOnCallProperties().getSandbox());
    }

    @Test
    void sandboxValidateShouldRejectKubernetesQuantitiesAboveHardCeilings() {
        KubeOnCallProperties.Sandbox sandbox = new KubeOnCallProperties().getSandbox();
        // §7.1 ceilings: CPU 1 core, memory 1Gi, ephemeral 2Gi.
        sandbox.setCpu("2");
        sandbox.setMemory("2Gi");
        sandbox.setEphemeralStorage("3Gi");

        IllegalStateException ex = assertThrows(IllegalStateException.class, sandbox::validate);
        String message = ex.getMessage();
        assertTrue(message.contains("cpu"), message);
        assertTrue(message.contains("memory"), message);
        assertTrue(message.contains("ephemeralStorage"), message);
    }

    @Test
    void sandboxValidateShouldRejectMalformedKubernetesQuantities() {
        KubeOnCallProperties.Sandbox sandbox = new KubeOnCallProperties().getSandbox();
        sandbox.setCpu("banana");
        sandbox.setMemory("1Xi");
        sandbox.setEphemeralStorage("-2Gi");

        IllegalStateException ex = assertThrows(IllegalStateException.class, sandbox::validate);
        String message = ex.getMessage();
        assertTrue(message.contains("cpu"), message);
        assertTrue(message.contains("memory"), message);
        assertTrue(message.contains("ephemeralStorage"), message);
    }

    @Test
    void sandboxValidateShouldAcceptBoundaryAndAlternateQuantityForms() {
        KubeOnCallProperties.Sandbox sandbox = new KubeOnCallProperties().getSandbox();
        // Exactly at ceilings, plus accepted alternate forms (millicores, decimal cores, Mi suffix).
        sandbox.setCpu("1000m");
        sandbox.setMemory("1024Mi");
        sandbox.setEphemeralStorage("2Gi");
        assertDoesNotThrowValidate(sandbox);

        sandbox.setCpu("1");
        sandbox.setMemory("1Gi");
        sandbox.setEphemeralStorage("2048Mi");
        assertDoesNotThrowValidate(sandbox);
    }

    @Test
    void sandboxValidateShouldRejectValuesJustOverCeilingWithoutRounding() {
        // Integer-unit values just over the ceiling must trip the exact ceiling comparison, not round
        // down past it. 1001m is one millicore over the 1-core ceiling; 1073741825 is one byte over 1Gi.
        KubeOnCallProperties.Sandbox sandbox = new KubeOnCallProperties().getSandbox();
        sandbox.setCpu("1001m");
        sandbox.setMemory("1073741825");

        IllegalStateException ex = assertThrows(IllegalStateException.class, sandbox::validate);
        String message = ex.getMessage();
        assertTrue(message.contains("cpu"), message);
        assertTrue(message.contains("memory"), message);
    }

    @Test
    void sandboxValidateShouldRejectFractionalQuantitiesThatDoNotMapToWholeUnits() {
        // Fractional quantities that do not resolve to a whole unit (millicore or byte) are rejected
        // rather than silently rounded, so a hair-above-ceiling value can never round down to it.
        KubeOnCallProperties.Sandbox sandbox = new KubeOnCallProperties().getSandbox();
        sandbox.setCpu("1.0001");
        sandbox.setMemory("1.0000001Gi");

        IllegalStateException ex = assertThrows(IllegalStateException.class, sandbox::validate);
        String message = ex.getMessage();
        assertTrue(message.contains("cpu"), message);
        assertTrue(message.contains("memory"), message);
    }

    @Test
    void sandboxValidateShouldRejectJavaOnlyNumericFormsThatKubernetesWouldReject() {
        KubeOnCallProperties.Sandbox sandbox = new KubeOnCallProperties().getSandbox();
        sandbox.setCpu("0x1.0p0");
        sandbox.setMemory("1f");

        IllegalStateException ex = assertThrows(IllegalStateException.class, sandbox::validate);
        String message = ex.getMessage();
        assertTrue(message.contains("cpu"), message);
        assertTrue(message.contains("memory"), message);
    }

    @Test
    void sandboxValidateShouldRejectExponentInMillicoresSuffix() {
        // Kubernetes does not allow an exponent alongside the millicores suffix; BigDecimal alone would
        // accept "1e3m" as 1000 millicores, so the grammar check must reject it.
        KubeOnCallProperties.Sandbox sandbox = new KubeOnCallProperties().getSandbox();
        sandbox.setCpu("1e3m");

        IllegalStateException ex = assertThrows(IllegalStateException.class, sandbox::validate);
        assertTrue(ex.getMessage().contains("cpu"), ex.getMessage());
    }

    @Test
    void sandboxValidateShouldNormalizeSurroundingWhitespaceOnQuantities() {
        KubeOnCallProperties.Sandbox sandbox = new KubeOnCallProperties().getSandbox();
        sandbox.setCpu(" 1 ");
        sandbox.setMemory(" 1Gi ");
        sandbox.setEphemeralStorage(" 2Gi ");
        sandbox.validate();

        // The canonical trimmed form is what capabilities and downstream consumers see.
        assertEquals("1", sandbox.getCpu());
        assertEquals("1Gi", sandbox.getMemory());
        assertEquals("2Gi", sandbox.getEphemeralStorage());
    }

    @Test
    void sandboxValidateShouldRejectControllerCallLimits() {
        KubeOnCallProperties.Sandbox sandbox = new KubeOnCallProperties().getSandbox();
        sandbox.setControllerConnectTimeoutMillis(0);
        sandbox.setControllerReadTimeoutMillis(-1);
        sandbox.setControllerMaxResponseBytes(Long.MAX_VALUE);

        IllegalStateException ex = assertThrows(IllegalStateException.class, sandbox::validate);
        String message = ex.getMessage();
        assertTrue(message.contains("controllerConnectTimeoutMillis"), message);
        assertTrue(message.contains("controllerReadTimeoutMillis"), message);
        assertTrue(message.contains("controllerMaxResponseBytes"), message);
    }

    @Test
    void sandboxValidateRequiresAuthenticatedControllerWhenMasterSwitchIsEnabled() {
        KubeOnCallProperties.Sandbox sandbox = new KubeOnCallProperties().getSandbox();
        sandbox.setEnabled(true);

        IllegalStateException missing = assertThrows(IllegalStateException.class, sandbox::validate);
        assertTrue(missing.getMessage().contains("controllerEndpoint"), missing.getMessage());
        assertTrue(missing.getMessage().contains("controllerHmacSecret"), missing.getMessage());

        sandbox.setControllerEndpoint("http://sandbox-controller:8088");
        sandbox.setControllerKeyId("backend");
        sandbox.setControllerHmacSecret("test-only-secret");
        assertDoesNotThrowValidate(sandbox);
    }

    private static void assertDoesNotThrowValidate(KubeOnCallProperties.Sandbox sandbox) {
        sandbox.validate();
    }
}
