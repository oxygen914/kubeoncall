package com.kubeoncall.web.api.v1.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;

class CapabilitiesServiceTest {

    @Test
    void shouldReportSandboxDisabledByDefaultWithNoSecretsOrLimitsLeakage() {
        CapabilitiesService service = newService(new KubeOnCallProperties());

        Map<String, Object> features = service.features();
        Map<String, Object> sandbox = sandboxFeatureMap(features);

        assertEquals(false, sandbox.get("enabled"));
        assertEquals(false, sandbox.get("fixedDiagnostic"));
        assertEquals(false, sandbox.get("generatedCode"));
        assertEquals(false, sandbox.get("manifestValidation"));
        assertEquals(false, sandbox.get("remediationSimulation"));
        assertEquals(false, sandbox.get("agentAutoRouteEnabled"));

        // When the master switch is off, sandbox limits are not advertised at all.
        assertFalse(service.limits().containsKey("sandbox"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldEchoSandboxCapabilitiesAndLimitsWhenEnabledWithoutSecrets() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        KubeOnCallProperties.Sandbox sandbox = properties.getSandbox();
        sandbox.setEnabled(true);
        sandbox.setFixedDiagnostic(true);
        sandbox.setGeneratedCode(true);
        sandbox.setManifestValidation(true);
        sandbox.setRemediationSimulation(false);
        sandbox.setAgentAutoRouteEnabled(true);
        sandbox.setControllerEndpoint("http://controller.svc:8090/internal/v1");
        sandbox.setControllerHmacSecret("test-hmac-secret-never-returned");

        CapabilitiesService service = newService(properties);

        Map<String, Object> sandboxFeatures = sandboxFeatureMap(service.features());
        assertEquals(true, sandboxFeatures.get("enabled"));
        assertEquals(true, sandboxFeatures.get("fixedDiagnostic"));
        assertEquals(true, sandboxFeatures.get("generatedCode"));
        assertEquals(true, sandboxFeatures.get("manifestValidation"));
        assertEquals(false, sandboxFeatures.get("remediationSimulation"));
        assertEquals(true, sandboxFeatures.get("agentAutoRouteEnabled"));

        Map<String, Object> sandboxLimits =
                (Map<String, Object>) service.limits().get("sandbox");
        assertNotNull(sandboxLimits, "sandbox limits advertised when enabled");
        assertEquals(300, sandboxLimits.get("timeoutSeconds"));
        assertEquals("1", sandboxLimits.get("cpu"));
        assertEquals("1Gi", sandboxLimits.get("memory"));
        assertEquals(50L * 1024 * 1024, sandboxLimits.get("inputMaxBytes"));
    }

    @Test
    void shouldNeverEchoControllerEndpointOrSecretsThroughCapabilities() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getSandbox().setEnabled(true);
        properties.getSandbox().setControllerEndpoint("http://controller.svc:8090/internal/v1");
        properties.getSandbox().setControllerHmacSecret("test-hmac-secret-never-returned");

        CapabilitiesService service = newService(properties);
        String featuresJson = service.features().toString();
        String limitsJson = service.limits().toString();

        assertFalse(
                featuresJson.contains("controller.svc"),
                "controller endpoint must not appear in features: " + featuresJson);
        assertFalse(limitsJson.contains("controller"), "controller endpoint must not appear in limits: " + limitsJson);
        assertFalse(featuresJson.contains("test-hmac-secret-never-returned"));
        assertFalse(limitsJson.contains("test-hmac-secret-never-returned"));
        assertNull(service.auth().get("controllerEndpoint"));
    }

    private static CapabilitiesService newService(KubeOnCallProperties properties) {
        return new CapabilitiesService(properties, "0.1.0", "abc123", "local", 20, 100, 52428800L, 100000);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sandboxFeatureMap(Map<String, Object> features) {
        return (Map<String, Object>) features.get("sandbox");
    }
}
