package com.kubeoncall.rag.repository;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
public class ElasticsearchKnowledgeRepository implements KnowledgeRepository {

    private final List<KnowledgeDocument> store = new CopyOnWriteArrayList<>();

    @Override
    public void save(KnowledgeDocument document) {
        store.add(document);
    }

    @Override
    public List<KnowledgeDocument> search(RetrievalRequest request) {
        String normalizedQuery = request.question() == null ? "" : request.question().toLowerCase(Locale.ROOT);
        return store.stream()
                .filter(doc -> matchesQuery(doc, normalizedQuery))
                .filter(doc -> matchesFilters(doc, request.filters()))
                .sorted(Comparator.comparingInt((KnowledgeDocument doc) -> lexicalScore(doc, normalizedQuery)).reversed()
                        .thenComparing(KnowledgeDocument::createdAt).reversed())
                .limit(request.topK())
                .toList();
    }

    private boolean matchesQuery(KnowledgeDocument document, String query) {
        if (query.isBlank()) {
            return true;
        }
        String title = document.title() == null ? "" : document.title().toLowerCase(Locale.ROOT);
        String content = document.content() == null ? "" : document.content().toLowerCase(Locale.ROOT);
        return title.contains(query) || content.contains(query) || lexicalScore(document, query) > 0;
    }

    private boolean matchesFilters(KnowledgeDocument document, java.util.Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        if (document.metadata() == null || document.metadata().isEmpty()) {
            return false;
        }
        return filters.entrySet().stream().allMatch(entry -> {
            String actual = document.metadata().get(entry.getKey());
            return actual != null && actual.equalsIgnoreCase(entry.getValue());
        });
    }

    private int lexicalScore(KnowledgeDocument document, String query) {
        if (query.isBlank()) {
            return 0;
        }
        String[] terms = query.split("\\s+");
        String haystack = ((document.title() == null ? "" : document.title()) + " "
                + (document.content() == null ? "" : document.content())).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (!term.isBlank() && haystack.contains(term)) {
                score++;
            }
        }
        return score;
    }
}
