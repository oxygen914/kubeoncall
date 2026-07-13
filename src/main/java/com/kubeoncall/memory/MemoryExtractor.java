package com.kubeoncall.memory;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.domain.graph.GraphState;

public interface MemoryExtractor {

    void extractFromAsk(GraphState state, String answer);

    void extractFromAlarm(NormalizedAlarmEvent event, String summary);

    static MemoryExtractor noop() {
        return new MemoryExtractor() {
            @Override
            public void extractFromAsk(GraphState state, String answer) {}

            @Override
            public void extractFromAlarm(NormalizedAlarmEvent event, String summary) {}
        };
    }
}
