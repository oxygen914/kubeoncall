package com.kubeoncall.alarm.correlation;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Repository;

/** Bounded local store; callers may replace this interface with an audit/ES-backed repository. */
@Repository
public class InMemoryChangeEventRepository implements ChangeEventRepository {

    private static final int MAX_EVENTS = 10_000;
    private final CopyOnWriteArrayList<ChangeEvent> events = new CopyOnWriteArrayList<>();
    private final java.util.Set<String> changeIds = ConcurrentHashMap.newKeySet();

    @Override
    public void save(ChangeEvent event) {
        saveIfAbsent(event);
    }

    @Override
    public boolean saveIfAbsent(ChangeEvent event) {
        if (event == null || !changeIds.add(event.changeId())) {
            return false;
        }
        events.add(event);
        if (events.size() > MAX_EVENTS) {
            events.stream().min(Comparator.comparing(ChangeEvent::changedAt)).ifPresent(oldest -> {
                events.remove(oldest);
                changeIds.remove(oldest.changeId());
            });
        }
        return true;
    }

    @Override
    public List<ChangeEvent> findBetween(Instant from, Instant to, String cluster, String namespace) {
        return events.stream()
                .filter(event ->
                        !event.changedAt().isBefore(from) && !event.changedAt().isAfter(to))
                .filter(event -> equalsOrUnspecified(cluster, event.cluster()))
                .filter(event -> equalsOrUnspecified(namespace, event.namespace()))
                .sorted(Comparator.comparing(ChangeEvent::changedAt).reversed())
                .toList();
    }

    private boolean equalsOrUnspecified(String expected, String actual) {
        return expected == null || expected.isBlank() || actual == null || actual.isBlank() || expected.equals(actual);
    }
}
