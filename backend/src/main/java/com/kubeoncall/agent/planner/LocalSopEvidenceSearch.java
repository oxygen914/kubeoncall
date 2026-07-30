package com.kubeoncall.agent.planner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.domain.rag.RetrieveMethod;
import com.kubeoncall.rag.KnowledgeRetrievalFacade;

/**
 * Bridges the built-in RAG index into the Planner's {@code knowledge.searchSop} evidence contract.
 *
 * <p>The external MCP tool remains preferred when it is available. A self-contained deployment can
 * still use the versioned runbooks already imported into Elasticsearch without fabricating a
 * response or requiring a second knowledge service.
 */
@Component
public class LocalSopEvidenceSearch {

    private static final int TOP_K = 3;
    private static final int MAX_SNIPPET_CHARS = 6000;
    private static final Pattern EXPLICIT_RUNBOOK = Pattern.compile("(?i)(runbook-[a-z0-9-]+)(?:@([a-z0-9._-]+))?");

    private final KnowledgeRetrievalFacade retrievalFacade;

    public LocalSopEvidenceSearch(KnowledgeRetrievalFacade retrievalFacade) {
        this.retrievalFacade = retrievalFacade;
    }

    public Map<String, Object> search(String query) {
        RetrievalResult result = retrievalFacade.retrieve(query, filters(query), TOP_K, RetrieveMethod.HYBRID, true);
        if (result.documents() == null || result.documents().isEmpty()) {
            return Map.of(
                    "tool",
                    "knowledge.searchSop",
                    "source",
                    "knowledge.rag.local",
                    "collectionStatus",
                    "EMPTY",
                    "simulation",
                    false,
                    "errorType",
                    "NO_MATCHING_SOP",
                    "summary",
                    "No matching versioned runbook was found in the local RAG index");
        }

        KnowledgeDocument document = result.documents().get(0);
        Map<String, String> metadata = document.metadata() == null ? Map.of() : document.metadata();
        String runbookId = first(metadata, "runbookId", "runbook_id", "documentId");
        String version = first(metadata, "runbook_version", "dataset_version", "version");

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("tool", "knowledge.searchSop");
        evidence.put("source", "knowledge.rag.local");
        evidence.put("collectionStatus", "SUCCEEDED");
        evidence.put("simulation", false);
        evidence.put("transport", "LOCAL_RAG");
        evidence.put("summary", "Matched versioned SOP " + reference(runbookId, version, document.title()));
        evidence.put("snippet", truncate(document.content(), MAX_SNIPPET_CHARS));
        evidence.put("sopId", runbookId);
        evidence.put("version", version);
        evidence.put("title", document.title());
        evidence.put("documentId", document.id());
        evidence.put("artifactReference", "knowledge:" + document.id());
        evidence.put("retrievalRoute", result.route());
        evidence.put("matchedDocuments", summaries(result.documents()));
        return Map.copyOf(evidence);
    }

    private static Map<String, String> filters(String query) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("source_type", "runbook");
        Matcher matcher = EXPLICIT_RUNBOOK.matcher(safe(query));
        String runbookId = "";
        String version = "";
        while (matcher.find()) {
            // Planner context is prepended before "Current user". Prefer the final explicit
            // reference so stale memory or Skill context cannot override the user's requested SOP.
            runbookId = matcher.group(1);
            version = matcher.group(2);
        }
        if (!runbookId.isBlank()) {
            filters.put("runbookId", runbookId.toLowerCase());
            if (version != null && !version.isBlank()) {
                filters.put("dataset_version", version.toLowerCase());
            }
        }
        return Map.copyOf(filters);
    }

    private static List<Map<String, String>> summaries(List<KnowledgeDocument> documents) {
        return documents.stream()
                .limit(TOP_K)
                .map(document -> {
                    Map<String, String> metadata = document.metadata() == null ? Map.of() : document.metadata();
                    Map<String, String> summary = new LinkedHashMap<>();
                    summary.put("documentId", safe(document.id()));
                    summary.put("title", safe(document.title()));
                    summary.put("runbookId", first(metadata, "runbookId", "runbook_id"));
                    summary.put("version", first(metadata, "runbook_version", "dataset_version", "version"));
                    return Map.copyOf(summary);
                })
                .toList();
    }

    private static String reference(String runbookId, String version, String title) {
        if (!runbookId.isBlank()) {
            return version.isBlank() ? runbookId : runbookId + "@" + version;
        }
        return safe(title);
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String truncate(String value, int maxChars) {
        String safe = safe(value);
        return safe.length() <= maxChars ? safe : safe.substring(0, maxChars) + "\n[TRUNCATED]";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
