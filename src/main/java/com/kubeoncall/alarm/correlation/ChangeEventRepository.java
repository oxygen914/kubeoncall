package com.kubeoncall.alarm.correlation;

import java.time.Instant;
import java.util.List;

public interface ChangeEventRepository {

    void save(ChangeEvent event);

    List<ChangeEvent> findBetween(Instant from, Instant to, String cluster, String namespace);
}
