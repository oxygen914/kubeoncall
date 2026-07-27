package com.kubeoncall.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.kubeoncall.sandbox.domain.SandboxRunMode;

/** Regression matrix for the global switch and one mode switch: 00/10/01/11. */
class SandboxFeatureFlagMatrixTest {

    @ParameterizedTest(name = "global={0}, mode={1} permits={2}")
    @MethodSource("flagCombinations")
    void shouldRequireBothGlobalAndModeSwitch(boolean globalEnabled, boolean modeEnabled, boolean expected) {
        for (SandboxRunMode mode : SandboxRunMode.values()) {
            KubeOnCallProperties properties = new KubeOnCallProperties();
            properties.getSandbox().setEnabled(globalEnabled);
            setMode(properties, mode, modeEnabled);

            assertThat(properties.getSandbox().isModeEnabled(mode)).isEqualTo(expected);
        }
    }

    private static Stream<Arguments> flagCombinations() {
        return Stream.of(
                Arguments.of(false, false, false),
                Arguments.of(true, false, false),
                Arguments.of(false, true, false),
                Arguments.of(true, true, true));
    }

    private static void setMode(KubeOnCallProperties properties, SandboxRunMode mode, boolean enabled) {
        switch (mode) {
            case FIXED_DIAGNOSTIC -> properties.getSandbox().setFixedDiagnostic(enabled);
            case GENERATED_CODE -> properties.getSandbox().setGeneratedCode(enabled);
            case MANIFEST_VALIDATION -> properties.getSandbox().setManifestValidation(enabled);
            case REMEDIATION_SIMULATION -> properties.getSandbox().setRemediationSimulation(enabled);
        }
    }
}
