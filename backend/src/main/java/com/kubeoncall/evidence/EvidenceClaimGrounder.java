package com.kubeoncall.evidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.task.TaskType;

/**
 * Replaces free-form read-only claims with a deterministic, source-attributed evidence summary.
 *
 * <p>The model still selects the intent, target and task, while the persisted conclusion reflects
 * the actual collection statuses. This prevents a successful evidence item from being described as
 * unavailable by model prose.
 */
@Component
public class EvidenceClaimGrounder {

    private static final int MAX_DETAIL_CHARS = 220;
    private static final int MAX_CLAIM_CHARS = 3900;

    public GroundedClaim ground(
            TaskType taskType, String target, String modelClaim, List<String> missingSignals, Object rawEvidence) {
        List<EvidenceItem> evidence = evidence(rawEvidence);
        if (!isReadOnlyQuery(taskType) || evidence.isEmpty()) {
            return new GroundedClaim(safe(modelClaim), safeList(missingSignals));
        }

        List<String> collected = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (EvidenceItem item : evidence) {
            String formatted = format(item);
            if (item.collectionStatus() == EvidenceCollectionStatus.SUCCEEDED
                    || item.collectionStatus() == EvidenceCollectionStatus.EMPTY) {
                collected.add(formatted);
            } else {
                unavailable.add(formatted);
            }
        }

        StringBuilder claim = new StringBuilder("Read-only evidence assessment for ")
                .append(safe(target).isBlank() ? "current scope" : safe(target))
                .append(". ");
        if (!collected.isEmpty()) {
            claim.append("Collected evidence: ")
                    .append(String.join("; ", collected))
                    .append(". ");
        }
        if (!unavailable.isEmpty()) {
            claim.append("Unavailable or failed sources: ")
                    .append(String.join("; ", unavailable))
                    .append(". ");
        }
        claim.append("This conclusion does not authorize any mutation.");
        return new GroundedClaim(
                abbreviate(claim.toString(), MAX_CLAIM_CHARS), reconcileMissingSignals(missingSignals, evidence));
    }

    private List<String> reconcileMissingSignals(List<String> missingSignals, List<EvidenceItem> evidence) {
        if (missingSignals == null || missingSignals.isEmpty()) {
            return List.of();
        }
        return missingSignals.stream()
                .filter(signal -> !isResolved(signal, evidence))
                .distinct()
                .toList();
    }

    private boolean isResolved(String signal, List<EvidenceItem> evidence) {
        String normalized = safe(signal).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.contains("metric")) {
            return hasSucceeded(evidence, EvidenceType.METRIC, null);
        }
        if (normalized.contains("resourcesnapshot")) {
            return hasSucceeded(evidence, EvidenceType.RESOURCE_STATE, "kubernetes");
        }
        if (normalized.contains("previouslog")) {
            return hasObserved(evidence, EvidenceType.POD_LOG, null, true);
        }
        if (normalized.contains("currentlog") || normalized.equals("logs") || normalized.equals("log")) {
            return hasObserved(evidence, EvidenceType.POD_LOG, null, false);
        }
        if (normalized.contains("event")) {
            return hasObserved(evidence, EvidenceType.K8S_EVENT, null, null);
        }
        if (normalized.contains("alert")) {
            return hasObserved(evidence, EvidenceType.ALERT, null, null);
        }
        if (normalized.contains("topology")) {
            return hasObserved(evidence, EvidenceType.RESOURCE_STATE, "topology", null);
        }
        if (normalized.contains("servicemetadata") || normalized.equals("metadata")) {
            return hasObserved(evidence, EvidenceType.RESOURCE_STATE, "cmdb", null);
        }
        if (normalized.contains("sop")) {
            return hasSucceeded(evidence, EvidenceType.SOP, null);
        }
        return false;
    }

    private boolean hasSucceeded(List<EvidenceItem> evidence, EvidenceType type, String sourceFragment) {
        return evidence.stream()
                .anyMatch(item -> item.type() == type
                        && item.collectionStatus() == EvidenceCollectionStatus.SUCCEEDED
                        && sourceMatches(item, sourceFragment));
    }

    private boolean hasObserved(
            List<EvidenceItem> evidence, EvidenceType type, String sourceFragment, Boolean previous) {
        return evidence.stream()
                .anyMatch(item -> item.type() == type
                        && (item.collectionStatus() == EvidenceCollectionStatus.SUCCEEDED
                                || item.collectionStatus() == EvidenceCollectionStatus.EMPTY)
                        && sourceMatches(item, sourceFragment)
                        && (previous == null || previous == isPreviousLog(item)));
    }

    private boolean sourceMatches(EvidenceItem item, String sourceFragment) {
        return sourceFragment == null
                || item.source().toLowerCase(Locale.ROOT).contains(sourceFragment.toLowerCase(Locale.ROOT));
    }

    private String format(EvidenceItem item) {
        String mode = "";
        if (item.type() == EvidenceType.POD_LOG) {
            mode = isPreviousLog(item) ? "(previous)" : "(current)";
        }
        String detail = detail(item);
        return item.type().name() + mode + "@" + item.source() + "="
                + item.collectionStatus().name() + (detail.isBlank() ? "" : ": " + detail);
    }

    private String detail(EvidenceItem item) {
        String value;
        if (item.collectionStatus() == EvidenceCollectionStatus.SUCCEEDED) {
            value = item.snippet().isBlank() ? item.summary() : item.snippet();
        } else {
            value = item.errorType().isBlank() ? item.summary() : item.errorType();
        }
        return abbreviate(value.replaceAll("\\s+", " ").trim(), MAX_DETAIL_CHARS);
    }

    private boolean isPreviousLog(EvidenceItem item) {
        Object previous = item.metadata().get("previous");
        return Boolean.TRUE.equals(previous)
                || "true".equalsIgnoreCase(String.valueOf(previous))
                || item.errorType().toUpperCase(Locale.ROOT).startsWith("PREVIOUS_");
    }

    private boolean isReadOnlyQuery(TaskType taskType) {
        return taskType != null && taskType.name().startsWith("QUERY_");
    }

    private List<EvidenceItem> evidence(Object rawEvidence) {
        if (!(rawEvidence instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(EvidenceItem.class::isInstance)
                .map(EvidenceItem.class::cast)
                .toList();
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return safe(value);
        }
        return value.substring(0, maxLength);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record GroundedClaim(String claim, List<String> missingSignals) {

        public GroundedClaim {
            claim = safe(claim);
            missingSignals = missingSignals == null ? List.of() : List.copyOf(new LinkedHashSet<>(missingSignals));
        }
    }
}
