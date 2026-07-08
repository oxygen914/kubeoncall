package com.kubeoncall.alarm.policy;

import com.kubeoncall.alarm.domain.AlarmPolicy;

import java.util.List;
import java.util.Optional;

/**
 * Read access to the configured alarm policies.
 *
 * <p>Implementations load from a declarative source (YAML by default) and validate policies on
 * load. The repository is a read-only view; mutation/reload is out of scope for the first version.
 */
public interface AlarmPolicyRepository {

    /** All loaded policies, in declaration order. */
    List<AlarmPolicy> findAll();

    /** Look up a policy by its unique id. */
    Optional<AlarmPolicy> findById(String policyId);

    /** Look up a policy by its human-readable name (e.g. {@code HostHighCpuUsageP0}). */
    Optional<AlarmPolicy> findByName(String name);
}
