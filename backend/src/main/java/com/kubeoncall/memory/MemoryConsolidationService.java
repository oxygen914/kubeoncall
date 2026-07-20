package com.kubeoncall.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.service.KubeOnCallMetricsService;

@Service
public class MemoryConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationService.class);

    private final KnowledgeRepository knowledgeRepository;
    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metricsService;
    private final ExecutionAuditService auditService;
    private final MemoryTemporalNormalizer temporalNormalizer;
    private final MemorySimilarityService similarityService;

    public MemoryConsolidationService(
            KnowledgeRepository knowledgeRepository,
            KubeOnCallProperties properties,
            KubeOnCallMetricsService metricsService,
            ExecutionAuditService auditService,
            MemoryTemporalNormalizer temporalNormalizer) {
        this(knowledgeRepository, properties, metricsService, auditService, temporalNormalizer, null);
    }

    @Autowired
    public MemoryConsolidationService(
            KnowledgeRepository knowledgeRepository,
            KubeOnCallProperties properties,
            KubeOnCallMetricsService metricsService,
            ExecutionAuditService auditService,
            MemoryTemporalNormalizer temporalNormalizer,
            MemorySimilarityService similarityService) {
        this.knowledgeRepository = Objects.requireNonNull(knowledgeRepository, "knowledgeRepository");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.metricsService = Objects.requireNonNull(metricsService, "metricsService");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.temporalNormalizer = Objects.requireNonNull(temporalNormalizer, "temporalNormalizer");
        this.similarityService = similarityService;
    }

    public ConsolidationResult consolidate(Instant now, int scanLimit, boolean dryRun) {
        Instant startedAt = Instant.now();
        Instant reference = now == null ? Instant.now() : now;
        int limit = Math.max(1, scanLimit);
        double threshold = boundedThreshold(properties.getMemory().getDuplicateSimilarityThreshold());
        if (!properties.getMemory().isEnabled() || !properties.getMemory().isLongTermEnabled()) {
            recordMetric("disabled", 0);
            ConsolidationResult result =
                    new ConsolidationResult(0, 0, 0, 0, 0, 0, "disabled", limit, threshold, dryRun);
            audit(result, startedAt);
            return result;
        }

        RetrievalRequest request = new RetrievalRequest("", Map.of("source_type", "memory"), limit);
        List<DocumentCandidate> scanned = knowledgeRepository.searchLexical(request, limit).stream()
                .filter(this::isEnabledMemory)
                .map(document -> normalize(document, reference))
                .toList();
        List<KnowledgeDocument> candidates = scanned.stream()
                .map(DocumentCandidate::document)
                .sorted(Comparator.comparing(this::updatedAt).reversed())
                .toList();
        Map<String, List<KnowledgeDocument>> canonicalByGroup = new LinkedHashMap<>();
        Set<String> duplicateGroups = new LinkedHashSet<>();
        List<DuplicateMatch> duplicates = new ArrayList<>();

        for (KnowledgeDocument candidate : candidates) {
            String group = groupKey(candidate);
            List<KnowledgeDocument> canonicals = canonicalByGroup.computeIfAbsent(group, ignored -> new ArrayList<>());
            KnowledgeDocument canonical = canonicals.stream()
                    .filter(existing -> combinedSimilarity(existing, candidate) >= threshold)
                    .findFirst()
                    .orElse(null);
            if (canonical == null) {
                canonicals.add(candidate);
            } else {
                duplicates.add(new DuplicateMatch(candidate, canonical));
                duplicateGroups.add(group + "|" + canonical.id());
            }
        }

        int consolidated = 0;
        int normalizationEligible = (int)
                scanned.stream().filter(DocumentCandidate::normalizationChanged).count();
        int normalized = 0;
        if (!dryRun) {
            Set<String> duplicateIds = duplicates.stream()
                    .map(match -> match.duplicate().id())
                    .collect(java.util.stream.Collectors.toSet());
            for (DuplicateMatch duplicate : duplicates) {
                knowledgeRepository.save(softDeletedDuplicate(
                        duplicate.duplicate(), duplicate.canonical().id(), reference));
                consolidated++;
            }
            for (DocumentCandidate candidate : scanned) {
                if (candidate.normalizationChanged()
                        && !duplicateIds.contains(candidate.document().id())) {
                    knowledgeRepository.save(candidate.document());
                }
            }
            normalized = normalizationEligible;
        }
        String status = dryRun ? "dry_run" : "success";
        recordMetric(status, dryRun ? duplicates.size() : consolidated);
        recordNormalizationMetric(status, dryRun ? normalizationEligible : normalized);
        ConsolidationResult result = new ConsolidationResult(
                candidates.size(),
                duplicateGroups.size(),
                duplicates.size(),
                consolidated,
                normalizationEligible,
                normalized,
                status,
                limit,
                threshold,
                dryRun);
        audit(result, startedAt);
        return result;
    }

    private double combinedSimilarity(KnowledgeDocument left, KnowledgeDocument right) {
        double lexical = similarity(left.content(), right.content());
        return similarityService == null ? lexical : similarityService.similarity(left, right, lexical);
    }

    private KnowledgeDocument softDeletedDuplicate(KnowledgeDocument duplicate, String canonicalId, Instant deletedAt) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (duplicate.metadata() != null) {
            metadata.putAll(duplicate.metadata());
        }
        metadata.put("memory_enabled", "false");
        metadata.put("chunk_enable", "false");
        metadata.put("deleted_at", deletedAt.toString());
        metadata.put("updated_at", deletedAt.toString());
        metadata.put("delete_reason", "duplicate_consolidation");
        metadata.put("duplicate_of", canonicalId);
        return new KnowledgeDocument(
                duplicate.id(),
                duplicate.title(),
                duplicate.content(),
                duplicate.source(),
                metadata,
                duplicate.createdAt(),
                duplicate.embeddingText(),
                duplicate.embedding());
    }

    private String groupKey(KnowledgeDocument document) {
        Map<String, String> metadata = document.metadata() == null ? Map.of() : document.metadata();
        return String.join(
                "|",
                normalized(metadata.get("memory_type")),
                normalized(metadata.get("memory_scope")),
                normalized(metadata.get("service")),
                normalized(metadata.get("resource")),
                normalized(metadata.get("fingerprint")),
                normalized(metadata.getOrDefault("subject", document.title())));
    }

    private double similarity(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return normalized(left).equals(normalized(right)) ? 1.0d : 0.0d;
        }
        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        int totalTokenCount = leftTokens.size() + rightTokens.size();
        return totalTokenCount == 0 ? 0.0d : (2.0d * intersection.size()) / totalTokenCount;
    }

    private Set<String> tokens(String value) {
        String normalized = normalized(value).replaceAll("[^\\p{L}\\p{N}_-]+", " ");
        if (normalized.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(List.of(normalized.split("\\s+")));
    }

    private Instant updatedAt(KnowledgeDocument document) {
        Map<String, String> metadata = document.metadata() == null ? Map.of() : document.metadata();
        String raw = metadata.get("updated_at");
        if (raw != null && !raw.isBlank()) {
            try {
                return Instant.parse(raw);
            } catch (RuntimeException ex) {
                log.debug(
                        "Memory consolidation metadata timestamp is invalid; using creation time: errorType={}",
                        ex.getClass().getSimpleName());
            }
        }
        return document.createdAt() == null ? Instant.EPOCH : document.createdAt();
    }

    private boolean isEnabledMemory(KnowledgeDocument document) {
        if (document == null || document.metadata() == null) {
            return false;
        }
        return "memory".equalsIgnoreCase(document.metadata().get("source_type"))
                && !"false".equalsIgnoreCase(document.metadata().get("memory_enabled"));
    }

    private double boundedThreshold(double value) {
        if (Double.isNaN(value)) {
            return 0.92d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private DocumentCandidate normalize(KnowledgeDocument document, Instant reference) {
        try {
            MemoryTemporalNormalizer.DocumentNormalization result = temporalNormalizer.normalize(document, reference);
            return new DocumentCandidate(result.document(), result.changed());
        } catch (RuntimeException ex) {
            recordNormalizationMetric("failed", 1);
            return new DocumentCandidate(document, false);
        }
    }

    private String normalized(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private void recordMetric(String outcome, long count) {
        metricsService.recordMemory("consolidate", outcome, count);
    }

    private void recordNormalizationMetric(String outcome, long count) {
        metricsService.recordMemory("normalize", outcome, count);
    }

    private void audit(ConsolidationResult result, Instant startedAt) {
        try {
            auditService.recordMemoryOperation(
                    "consolidate",
                    result.status(),
                    "memory consolidation " + result.status(),
                    startedAt,
                    Map.of(
                            "scanned", result.scanned(),
                            "duplicateGroups", result.duplicateGroups(),
                            "eligible", result.eligible(),
                            "consolidated", result.consolidated(),
                            "normalizationEligible", result.normalizationEligible(),
                            "normalized", result.normalized(),
                            "dryRun", result.dryRun(),
                            "scanLimit", result.scanLimit(),
                            "similarityThreshold", result.similarityThreshold()));
        } catch (RuntimeException ex) {
            log.warn(
                    "Unable to audit memory consolidation: status={}, errorType={}",
                    result.status(),
                    ex.getClass().getSimpleName());
        }
    }

    public record ConsolidationResult(
            int scanned,
            int duplicateGroups,
            int eligible,
            int consolidated,
            int normalizationEligible,
            int normalized,
            String status,
            int scanLimit,
            double similarityThreshold,
            boolean dryRun) {}

    private record DuplicateMatch(KnowledgeDocument duplicate, KnowledgeDocument canonical) {}

    private record DocumentCandidate(KnowledgeDocument document, boolean normalizationChanged) {}
}
