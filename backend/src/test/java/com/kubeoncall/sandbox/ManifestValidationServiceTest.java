package com.kubeoncall.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ManifestValidationServiceTest {

    private final ManifestValidationService service = new ManifestValidationService();

    @Test
    void validatesBuiltinManifestAndRejectsUnknownCrdAndDangerousPodSpec() {
        var valid = service.validate(
                new ManifestValidationService.ValidationRequest(ManifestValidationService.Type.MANIFEST, """
                apiVersion: apps/v1
                kind: Deployment
                metadata: {name: api}
                spec: {replicas: 2}
                """, null));
        assertThat(valid.valid()).isTrue();
        assertThat(valid.toolVersions()).containsEntry("rulesetVersion", "sandbox-manifest-rules-v1");

        var unsafe = service.validate(
                new ManifestValidationService.ValidationRequest(ManifestValidationService.Type.MANIFEST, """
                apiVersion: stable.example/v1
                kind: Widget
                metadata: {name: dangerous}
                spec:
                  hostNetwork: true
                  containers: [{securityContext: {privileged: true}}]
                """, null));
        assertThat(unsafe.valid()).isFalse();
        assertThat(unsafe.findings())
                .extracting(ManifestValidationService.Finding::code)
                .contains("UNKNOWN_CRD", "OPA_HOST_NETWORK_DENIED", "OPA_PRIVILEGED_DENIED");
    }

    @Test
    void appliesJsonAndStrategicMergePatchesOnlyAgainstSnapshots() {
        String snapshot = "apiVersion: v1\nkind: ConfigMap\nmetadata: {name: api}\ndata: {mode: old}\n";
        var jsonPatch = service.validate(new ManifestValidationService.ValidationRequest(
                ManifestValidationService.Type.JSON_PATCH,
                "[{\"op\":\"replace\",\"path\":\"/data/mode\",\"value\":\"new\"}]",
                snapshot));
        assertThat(jsonPatch.valid()).isTrue();
        assertThat(jsonPatch.renderedOrPatched()).contains("\"mode\":\"new\"");

        var merge = service.validate(new ManifestValidationService.ValidationRequest(
                ManifestValidationService.Type.STRATEGIC_MERGE_PATCH,
                "data: {mode: merged, extra: enabled}",
                snapshot));
        assertThat(merge.valid()).isTrue();
        assertThat(merge.renderedOrPatched()).contains("\"mode\":\"merged\"").contains("\"extra\":\"enabled\"");
        assertThat(service.validate(new ManifestValidationService.ValidationRequest(
                                ManifestValidationService.Type.JSON_PATCH, "[]", null))
                        .valid())
                .isFalse();
    }

    @Test
    void rejectsMalformedHelmAndDangerousRunbookWithoutApprovalRollback() {
        var helm = service.validate(new ManifestValidationService.ValidationRequest(
                ManifestValidationService.Type.HELM,
                "apiVersion: v2\nname: demo\ntemplate: '{{ if .Values.enabled }}'\n",
                null));
        assertThat(helm.valid()).isFalse();
        assertThat(helm.findings())
                .extracting(ManifestValidationService.Finding::code)
                .contains("HELM_TEMPLATE_FAILED");

        var runbook = service.validate(
                new ManifestValidationService.ValidationRequest(ManifestValidationService.Type.RUNBOOK, """
                ---
                runbookId: restart-api
                title: Restart API
                version: v1
                ---
                ## Steps
                kubectl rollout restart deployment/api
                """, null));
        assertThat(runbook.valid()).isFalse();
        assertThat(runbook.findings())
                .extracting(ManifestValidationService.Finding::code)
                .contains("RUNBOOK_SECTION_MISSING", "RUNBOOK_DANGEROUS_ACTION");

        var approved = service.validate(
                new ManifestValidationService.ValidationRequest(ManifestValidationService.Type.RUNBOOK, """
                ---
                runbookId: scale-api
                title: Scale API
                version: v1
                approvalRequired: true
                ---
                ## Steps
                kubectl scale deployment/api --replicas=3
                ## Rollback
                kubectl scale deployment/api --replicas=1
                """, null));
        assertThat(approved.valid()).isTrue();
    }
}
