package com.kubeoncall.rag.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.index.AliasAction;
import org.springframework.data.elasticsearch.core.index.AliasActionParameters;
import org.springframework.data.elasticsearch.core.index.AliasActions;
import org.springframework.data.elasticsearch.core.index.AliasData;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Component;

/** Reads and atomically switches the configured knowledge index alias. */
@Component
public class KnowledgeIndexAliasManager {

    private final ElasticsearchTemplate elasticsearchTemplate;
    private final KnowledgeIndexNaming indexNaming;

    public KnowledgeIndexAliasManager(ElasticsearchTemplate elasticsearchTemplate, KnowledgeIndexNaming indexNaming) {
        this.elasticsearchTemplate = elasticsearchTemplate;
        this.indexNaming = indexNaming;
    }

    public AliasSnapshot status() {
        if (!indexNaming.aliasEnabled()) {
            return new AliasSnapshot("", "", List.of());
        }
        if (!elasticsearchTemplate.indexOps(indexNaming.activeIndex()).exists()) {
            return new AliasSnapshot(indexNaming.aliasName(), "", List.of());
        }
        Map<String, Set<AliasData>> aliases =
                elasticsearchTemplate.indexOps(indexNaming.activeIndex()).getAliases(indexNaming.aliasName());
        List<String> backingIndices =
                aliases == null ? List.of() : aliases.keySet().stream().sorted().toList();
        String activeIndex = backingIndices.size() == 1 ? backingIndices.get(0) : "";
        return new AliasSnapshot(indexNaming.aliasName(), activeIndex, backingIndices);
    }

    public boolean activateInitial(String target) {
        return apply(target, List.of());
    }

    public AliasSnapshot activateVersion(String target) {
        if (!elasticsearchTemplate.indexOps(IndexCoordinates.of(target)).exists()) {
            throw new IllegalArgumentException(
                    "Knowledge version does not exist: " + target.substring(target.lastIndexOf('-') + 1));
        }
        AliasSnapshot current = status();
        if (current.backingIndices().isEmpty()
                && elasticsearchTemplate.indexOps(indexNaming.activeIndex()).exists()) {
            throw new IllegalStateException(
                    "Configured knowledge-index-alias points to a concrete legacy index; migrate it before activation");
        }
        if (!apply(target, current.backingIndices())) {
            throw new IllegalStateException("Failed to activate knowledge alias " + indexNaming.aliasName());
        }
        return status();
    }

    private boolean apply(String target, List<String> removeBackings) {
        List<AliasAction> actions = new ArrayList<>();
        for (String backing : removeBackings) {
            actions.add(new AliasAction.Remove(AliasActionParameters.builder()
                    .withIndices(backing)
                    .withAliases(indexNaming.aliasName())
                    .build()));
        }
        actions.add(new AliasAction.Add(AliasActionParameters.builder()
                .withIndices(target)
                .withAliases(indexNaming.aliasName())
                .withIsWriteIndex(true)
                .build()));
        return elasticsearchTemplate
                .indexOps(IndexCoordinates.of(target))
                .alias(new AliasActions(actions.toArray(AliasAction[]::new)));
    }

    public record AliasSnapshot(String alias, String activeIndex, List<String> backingIndices) {

        public AliasSnapshot {
            backingIndices = List.copyOf(backingIndices);
        }
    }
}
