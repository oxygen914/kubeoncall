package com.kubeoncall.evidence;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.domain.graph.GraphState;

/** Atomically projects in-memory Evidence/Conclusion facts into the durable execution read model. */
@Service
public class EvidencePersistenceService {

    private final ObjectProvider<EvidenceRepository> evidenceRepositoryProvider;
    private final ObjectProvider<ConclusionRepository> conclusionRepositoryProvider;
    private final ObjectMapper objectMapper;

    public EvidencePersistenceService(
            ObjectProvider<EvidenceRepository> evidenceRepositoryProvider,
            ObjectProvider<ConclusionRepository> conclusionRepositoryProvider,
            ObjectMapper objectMapper) {
        this.evidenceRepositoryProvider = evidenceRepositoryProvider;
        this.conclusionRepositoryProvider = conclusionRepositoryProvider;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return evidenceRepositoryProvider.getIfAvailable() != null
                && conclusionRepositoryProvider.getIfAvailable() != null;
    }

    @Transactional
    public void persist(GraphState state) {
        if (!isAvailable() || state == null || state.getExecutionId() == null) {
            return;
        }
        EvidenceRepository evidenceRepository = evidenceRepositoryProvider.getObject();
        ConclusionRepository conclusionRepository = conclusionRepositoryProvider.getObject();
        for (EvidenceItem item : typed(state.getContext().get("evidenceItems"), EvidenceItem.class)) {
            evidenceRepository.upsert(state.getExecutionId(), item);
        }
        for (AiConclusion conclusion : typed(state.getContext().get("conclusions"), AiConclusion.class)) {
            conclusionRepository.upsert(state.getExecutionId(), conclusion);
        }
    }

    private <T> List<T> typed(Object value, Class<T> type) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> convert(item, type))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private <T> T convert(Object value, Class<T> type) {
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        try {
            return objectMapper.convertValue(value, type);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
