package com.kubeoncall.sandbox;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Deterministic, read-only manifest and Runbook preflight validation.
 *
 * <p>The service never calls Helm, kubectl, OPA or a cluster. The fixed SBX-17 runtime repeats
 * this contract with pinned kubeconform/Helm/Conftest binaries; this preflight gives callers an
 * immediate, auditable rejection for malformed documents and makes Patch diffs reproducible before
 * the runtime is dispatched.
 */
@Service
public class ManifestValidationService {

    private static final String RULESET_VERSION = "sandbox-manifest-rules-v1";
    private static final Map<String, Set<String>> BUILTIN_KINDS = Map.of(
            "v1", Set.of("ConfigMap", "Secret", "Service", "Namespace", "ServiceAccount", "Pod"),
            "apps/v1", Set.of("Deployment", "StatefulSet", "DaemonSet", "ReplicaSet"),
            "batch/v1", Set.of("Job", "CronJob"),
            "networking.k8s.io/v1", Set.of("Ingress", "NetworkPolicy"),
            "rbac.authorization.k8s.io/v1", Set.of("Role", "RoleBinding", "ClusterRole", "ClusterRoleBinding"));
    private static final Set<String> DANGEROUS_RUNBOOK = Set.of(
            "kubectl delete",
            "kubectl apply",
            "kubectl patch",
            "kubectl scale",
            "kubectl rollout restart",
            "helm upgrade");
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    /** Validates a manifest or Runbook and returns only structured findings/diffs, never a command. */
    public ValidationResult validate(ValidationRequest request) {
        if (request == null || request.type() == null || blank(request.document())) {
            throw new IllegalArgumentException("validation type and document are required");
        }
        List<Finding> findings = new ArrayList<>();
        JsonNode rendered =
                switch (request.type()) {
                    case MANIFEST -> parseManifest(request.document(), findings);
                    case HELM -> validateHelmInput(request.document(), findings);
                    case JSON_PATCH -> applyJsonPatch(request.snapshot(), request.document(), findings);
                    case STRATEGIC_MERGE_PATCH -> applyStrategicMerge(request.snapshot(), request.document(), findings);
                    case RUNBOOK -> validateRunbook(request.document(), findings);
                };
        if (rendered != null && (request.type() == Type.MANIFEST || request.type() == Type.HELM)) {
            applyPolicy(rendered, findings);
        }
        return new ValidationResult(
                findings.stream().noneMatch(Finding::error),
                List.copyOf(findings),
                rendered == null ? null : canonical(rendered),
                Map.of(
                        "yamlParser", "jackson-yaml",
                        "kubernetesSchema", "builtin-kinds-v1",
                        "helm", "template-contract-v1",
                        "conftest", RULESET_VERSION,
                        "rulesetVersion", RULESET_VERSION));
    }

    private JsonNode parseManifest(String document, List<Finding> findings) {
        try {
            JsonNode root = yaml.readTree(document);
            if (root == null || !root.isObject()) {
                findings.add(error("YAML_DOCUMENT_INVALID", "manifest must be one YAML object"));
                return null;
            }
            String apiVersion = text(root, "apiVersion");
            String kind = text(root, "kind");
            String name = root.path("metadata").path("name").asText();
            if (apiVersion.isBlank() || kind.isBlank() || name.isBlank()) {
                findings.add(error("KUBERNETES_REQUIRED_FIELDS", "apiVersion, kind and metadata.name are required"));
                return root;
            }
            if (!BUILTIN_KINDS.getOrDefault(apiVersion, Set.of()).contains(kind)) {
                findings.add(error("UNKNOWN_CRD", "unknown Kubernetes apiVersion/kind requires an explicit schema"));
            }
            return root;
        } catch (Exception ex) {
            findings.add(error("YAML_PARSE_FAILED", "invalid YAML"));
            return null;
        }
    }

    private JsonNode validateHelmInput(String document, List<Finding> findings) {
        try {
            JsonNode root = yaml.readTree(document);
            if (root == null || !root.isObject()) {
                findings.add(error("HELM_INPUT_INVALID", "Helm input must be a YAML object"));
                return null;
            }
            if (text(root, "apiVersion").isBlank() || text(root, "name").isBlank()) {
                findings.add(error("HELM_CHART_METADATA_INVALID", "Chart.yaml apiVersion and name are required"));
            }
            String template = root.path("template").asText();
            if (template.isBlank()
                    || count(template, "{{") != count(template, "}}")
                    || helmControlBlocks(template) != count(template, "{{ end")) {
                findings.add(error("HELM_TEMPLATE_FAILED", "template delimiters are malformed"));
            }
            return root;
        } catch (Exception ex) {
            findings.add(error("HELM_INPUT_INVALID", "invalid Helm YAML input"));
            return null;
        }
    }

    private JsonNode applyJsonPatch(String snapshot, String patch, List<Finding> findings) {
        ObjectNode target = objectSnapshot(snapshot, findings);
        if (target == null) {
            return null;
        }
        try {
            JsonNode operations = json.readTree(patch);
            if (!operations.isArray()) {
                throw new IllegalArgumentException();
            }
            for (JsonNode operation : operations) {
                String op = operation.path("op").asText();
                String path = operation.path("path").asText();
                if (!("add".equals(op) || "replace".equals(op) || "remove".equals(op))
                        || !path.startsWith("/")
                        || path.contains("..")) {
                    throw new IllegalArgumentException();
                }
                applySinglePatch(target, op, path, operation.get("value"));
            }
        } catch (Exception ex) {
            findings.add(error("JSON_PATCH_INVALID", "patch must use safe add, replace or remove operations"));
            return target;
        }
        return target;
    }

    private JsonNode applyStrategicMerge(String snapshot, String patch, List<Finding> findings) {
        ObjectNode target = objectSnapshot(snapshot, findings);
        if (target == null) {
            return null;
        }
        try {
            JsonNode overlay = yaml.readTree(patch);
            if (overlay == null || !overlay.isObject()) {
                throw new IllegalArgumentException();
            }
            merge(target, overlay);
            return target;
        } catch (Exception ex) {
            findings.add(error("STRATEGIC_MERGE_INVALID", "patch must be a YAML object"));
            return target;
        }
    }

    private JsonNode validateRunbook(String document, List<Finding> findings) {
        int end = document.startsWith("---\n") ? document.indexOf("\n---", 4) : -1;
        if (end < 0) {
            findings.add(error("RUNBOOK_FRONTMATTER_INVALID", "Runbook requires YAML frontmatter"));
            return null;
        }
        try {
            Map<String, Object> metadata = yaml.readValue(document.substring(4, end), MAP_TYPE);
            String body = document.substring(end + 4).trim();
            for (String field : List.of("runbookId", "title", "version")) {
                if (blank(String.valueOf(metadata.getOrDefault(field, "")))) {
                    findings.add(error("RUNBOOK_METADATA_MISSING", "Runbook missing " + field));
                }
            }
            if (!body.contains("## Steps") || !body.contains("## Rollback")) {
                findings.add(error("RUNBOOK_SECTION_MISSING", "Runbook must contain Steps and Rollback sections"));
            }
            String lower = body.toLowerCase();
            boolean approvalRequired =
                    Boolean.parseBoolean(String.valueOf(metadata.getOrDefault("approvalRequired", false)));
            for (String action : DANGEROUS_RUNBOOK) {
                if (lower.contains(action) && !(approvalRequired && body.contains("## Rollback"))) {
                    findings.add(error(
                            "RUNBOOK_DANGEROUS_ACTION", "dangerous action requires approval metadata and rollback"));
                    break;
                }
            }
            return json.valueToTree(metadata);
        } catch (Exception ex) {
            findings.add(error("RUNBOOK_FRONTMATTER_INVALID", "invalid Runbook frontmatter"));
            return null;
        }
    }

    private void applyPolicy(JsonNode root, List<Finding> findings) {
        if (root.path("spec").path("hostNetwork").asBoolean(false)) {
            findings.add(error("OPA_HOST_NETWORK_DENIED", "hostNetwork is forbidden"));
        }
        for (JsonNode container : root.path("spec").path("containers")) {
            if (container.path("securityContext").path("privileged").asBoolean(false)) {
                findings.add(error("OPA_PRIVILEGED_DENIED", "privileged containers are forbidden"));
            }
        }
    }

    private ObjectNode objectSnapshot(String snapshot, List<Finding> findings) {
        if (blank(snapshot)) {
            findings.add(error("PATCH_SNAPSHOT_REQUIRED", "a snapshot is required before applying a patch"));
            return null;
        }
        try {
            JsonNode parsed = yaml.readTree(snapshot);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException();
            }
            return (ObjectNode) parsed.deepCopy();
        } catch (Exception ex) {
            findings.add(error("PATCH_SNAPSHOT_INVALID", "snapshot must be a YAML object"));
            return null;
        }
    }

    private void applySinglePatch(ObjectNode target, String op, String pointer, JsonNode value) {
        String[] segments = pointer.substring(1).split("/");
        ObjectNode parent = target;
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = unescape(segments[i]);
            JsonNode child = parent.get(segment);
            if (child == null || !child.isObject()) {
                if (!"add".equals(op)) {
                    throw new IllegalArgumentException();
                }
                child = parent.putObject(segment);
            }
            parent = (ObjectNode) child;
        }
        String leaf = unescape(segments[segments.length - 1]);
        if ("remove".equals(op)) {
            if (!parent.has(leaf)) {
                throw new IllegalArgumentException();
            }
            parent.remove(leaf);
        } else {
            if (value == null || ("replace".equals(op) && !parent.has(leaf))) {
                throw new IllegalArgumentException();
            }
            parent.set(leaf, value);
        }
    }

    private void merge(ObjectNode target, JsonNode overlay) {
        Iterator<Map.Entry<String, JsonNode>> fields = overlay.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode current = target.get(field.getKey());
            if (current != null && current.isObject() && field.getValue().isObject()) {
                merge((ObjectNode) current, field.getValue());
            } else {
                target.set(field.getKey(), field.getValue());
            }
        }
    }

    private String canonical(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot serialize validation result", ex);
        }
    }

    private static int count(String value, String token) {
        int count = 0;
        for (int index = value.indexOf(token); index >= 0; index = value.indexOf(token, index + token.length())) {
            count++;
        }
        return count;
    }

    private static int helmControlBlocks(String template) {
        return count(template, "{{ if")
                + count(template, "{{ range")
                + count(template, "{{ with")
                + count(template, "{{ define");
    }

    private static String unescape(String value) {
        return value.replace("~1", "/").replace("~0", "~");
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText().trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Finding error(String code, String message) {
        return new Finding(code, message, true);
    }

    public enum Type {
        MANIFEST,
        HELM,
        JSON_PATCH,
        STRATEGIC_MERGE_PATCH,
        RUNBOOK
    }

    public record ValidationRequest(Type type, String document, String snapshot) {}

    public record Finding(String code, String message, boolean error) {}

    public record ValidationResult(
            boolean valid, List<Finding> findings, String renderedOrPatched, Map<String, String> toolVersions) {}
}
