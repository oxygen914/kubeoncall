package com.kubeoncall.alarm.correlation;

import java.time.Instant;
import java.util.List;

public interface ChangeEventRepository {

    void save(ChangeEvent event);

    default boolean saveIfAbsent(ChangeEvent event) {
        save(event);
        return true;
    }

    List<ChangeEvent> findBetween(Instant from, Instant to, String cluster, String namespace);
}
