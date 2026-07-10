package com.kubeoncall.memory;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts deterministic relative dates in memory content to absolute calendar dates. */
@Component
public class MemoryTemporalNormalizer {

    private static final int MAX_OFFSET_DAYS = 3650;
    private static final Pattern EN_DAY_BEFORE_YESTERDAY = Pattern.compile(
            "(?i)\\bthe\\s+day\\s+before\\s+yesterday\\b");
    private static final Pattern EN_DAYS_AGO = Pattern.compile("(?i)\\b(\\d{1,4})\\s+days?\\s+ago\\b");
    private static final Pattern EN_WEEKS_AGO = Pattern.compile("(?i)\\b(\\d{1,3})\\s+weeks?\\s+ago\\b");
    private static final Pattern EN_YESTERDAY = Pattern.compile("(?i)\\byesterday\\b");
    private static final Pattern EN_TODAY = Pattern.compile("(?i)\\btoday\\b");
    private static final Pattern EN_LAST_WEEK = Pattern.compile("(?i)\\blast\\s+week\\b");
    private static final Pattern CN_DAYS_AGO = Pattern.compile("(?<!\\d)(\\d{1,4})\\s*天前");
    private static final Pattern CN_WEEKS_AGO = Pattern.compile("(?<!\\d)(\\d{1,3})\\s*周前");
    private static final Pattern AMBIGUOUS_RELATIVE_DATE = Pattern.compile(
            "(?i)上个?月|最近|近期|前些天|几天前|去年|前年|"
                    + "\\d{1,4}\\s*(?:天|周)前|"
                    + "\\d{1,4}\\s+(?:days?|weeks?|months?)\\s+ago|"
                    + "last\\s+(?:month|year)|recently|a\\s+few\\s+days\\s+ago");

    private final KubeOnCallProperties properties;

    public MemoryTemporalNormalizer(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    public MemoryEntry normalize(MemoryEntry entry, Instant normalizedAt) {
        if (entry == null || entry.content() == null || entry.content().isBlank()) {
            return entry;
        }
        Instant referenceInstant = entry.createdAt() == null ? normalizedAt : entry.createdAt();
        NormalizedText result = normalizeText(entry.content(), referenceInstant);
        Map<String, String> metadata = normalizedMetadata(
                entry.metadata(), result, normalizedAt == null ? Instant.now() : normalizedAt);
        if (entry.content().equals(result.content()) && metadata.equals(entry.metadata())) {
            return entry;
        }
        return new MemoryEntry(
                entry.id(), entry.type(), entry.scope(), entry.subject(), result.content(),
                entry.service(), entry.resource(), entry.fingerprint(), entry.createdAt(), entry.updatedAt(), metadata);
    }

    public DocumentNormalization normalize(KnowledgeDocument document, Instant normalizedAt) {
        if (document == null || document.content() == null || document.content().isBlank()) {
            return new DocumentNormalization(document, false, 0, 0);
        }
        Map<String, String> currentMetadata = document.metadata() == null ? Map.of() : document.metadata();
        Instant reference = parseInstant(currentMetadata.get("created_at"), document.createdAt());
        if (reference == null) {
            reference = normalizedAt == null ? Instant.now() : normalizedAt;
        }
        NormalizedText result = normalizeText(document.content(), reference);
        Map<String, String> metadata = normalizedMetadata(
                currentMetadata, result, normalizedAt == null ? Instant.now() : normalizedAt);
        boolean changed = !document.content().equals(result.content()) || !currentMetadata.equals(metadata);
        if (!changed) {
            return new DocumentNormalization(document, false, result.replacementCount(), result.unresolved().size());
        }
        KnowledgeDocument normalized = new KnowledgeDocument(
                document.id(), document.title(), result.content(), document.source(), metadata,
                document.createdAt(), document.embeddingText(), document.embedding());
        return new DocumentNormalization(
                normalized, true, result.replacementCount(), result.unresolved().size());
    }

    NormalizedText normalizeText(String content, Instant referenceInstant) {
        String current = content == null ? "" : content;
        ZoneId zone = normalizationZone();
        LocalDate reference = (referenceInstant == null ? Instant.now() : referenceInstant)
                .atZone(zone)
                .toLocalDate();
        List<String> replacements = new ArrayList<>();

        current = replace(current, EN_DAY_BEFORE_YESTERDAY,
                ignored -> reference.minusDays(2).toString(), replacements);
        current = replace(current, CN_DAYS_AGO,
                matcher -> offsetDate(reference, matcher.group(1), 1, matcher.group()), replacements);
        current = replace(current, EN_DAYS_AGO,
                matcher -> offsetDate(reference, matcher.group(1), 1, matcher.group()), replacements);
        current = replace(current, CN_WEEKS_AGO,
                matcher -> offsetDate(reference, matcher.group(1), 7, matcher.group()), replacements);
        current = replace(current, EN_WEEKS_AGO,
                matcher -> offsetDate(reference, matcher.group(1), 7, matcher.group()), replacements);
        current = replace(current, Pattern.compile("大前天"),
                ignored -> reference.minusDays(3).toString(), replacements);
        current = replace(current, Pattern.compile("前天"),
                ignored -> reference.minusDays(2).toString(), replacements);
        current = replace(current, Pattern.compile("昨天"),
                ignored -> reference.minusDays(1).toString(), replacements);
        current = replace(current, EN_YESTERDAY,
                ignored -> reference.minusDays(1).toString(), replacements);
        current = replace(current, Pattern.compile("今天"),
                ignored -> reference.toString(), replacements);
        current = replace(current, EN_TODAY,
                ignored -> reference.toString(), replacements);
        current = replace(current, Pattern.compile("上周"),
                ignored -> previousWeek(reference, "至"), replacements);
        current = replace(current, EN_LAST_WEEK,
                ignored -> previousWeek(reference, " to "), replacements);

        List<String> unresolved = findMatches(AMBIGUOUS_RELATIVE_DATE, current);
        return new NormalizedText(
                current, reference, zone.getId(), List.copyOf(replacements), unresolved);
    }

    private Map<String, String> normalizedMetadata(Map<String, String> original,
                                                   NormalizedText result,
                                                   Instant normalizedAt) {
        if (result.replacementCount() == 0 && result.unresolved().isEmpty()) {
            return original == null ? Map.of() : original;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        if (original != null) {
            metadata.putAll(original);
        }
        String status = result.unresolved().isEmpty()
                ? "normalized"
                : result.replacementCount() > 0 ? "partial" : "unresolved";
        Map<String, String> semantic = Map.of(
                "temporal_normalization_status", status,
                "temporal_normalization_zone", result.zoneId(),
                "temporal_normalization_reference", result.referenceDate().toString(),
                "temporal_normalization_count", String.valueOf(result.replacementCount()),
                "temporal_relative_expressions", String.join(" | ", result.replacements()),
                "temporal_unresolved_expressions", String.join(" | ", result.unresolved())
        );
        boolean semanticChanged = semantic.entrySet().stream()
                .anyMatch(entry -> !entry.getValue().equals(metadata.get(entry.getKey())));
        metadata.putAll(semantic);
        if (semanticChanged) {
            metadata.put("temporal_normalized_at", normalizedAt.toString());
        }
        return Map.copyOf(metadata);
    }

    private String replace(String input,
                           Pattern pattern,
                           Function<Matcher, String> replacement,
                           List<String> replacements) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group();
            String normalized = replacement.apply(matcher);
            if (normalized == null || normalized.equals(original)) {
                matcher.appendReplacement(output, Matcher.quoteReplacement(original));
                continue;
            }
            replacements.add(original + "=>" + normalized);
            matcher.appendReplacement(output, Matcher.quoteReplacement(normalized));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private String offsetDate(LocalDate reference, String rawOffset, int multiplier, String fallback) {
        try {
            long days = Math.multiplyExact(Long.parseLong(rawOffset), multiplier);
            return days >= 0 && days <= MAX_OFFSET_DAYS ? reference.minusDays(days).toString() : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private String previousWeek(LocalDate reference, String separator) {
        LocalDate start = reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        return start + separator + start.plusDays(6);
    }

    private List<String> findMatches(Pattern pattern, String content) {
        List<String> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String value = matcher.group().toLowerCase(Locale.ROOT);
            if (!matches.contains(value)) {
                matches.add(value);
            }
        }
        return List.copyOf(matches);
    }

    private ZoneId normalizationZone() {
        String configured = properties.getMemory().getTemporalNormalizationZone();
        try {
            return ZoneId.of(configured == null || configured.isBlank() ? "UTC" : configured.trim());
        } catch (RuntimeException ex) {
            return ZoneOffset.UTC;
        }
    }

    private Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    record NormalizedText(
            String content,
            LocalDate referenceDate,
            String zoneId,
            List<String> replacements,
            List<String> unresolved
    ) {
        int replacementCount() {
            return replacements.size();
        }
    }

    public record DocumentNormalization(
            KnowledgeDocument document,
            boolean changed,
            int replacementCount,
            int unresolvedCount
    ) {
    }
}
