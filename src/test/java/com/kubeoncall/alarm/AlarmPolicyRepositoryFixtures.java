package com.kubeoncall.alarm;

import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.policy.YamlAlarmPolicyRepository;
import com.kubeoncall.common.config.KubeOnCallProperties;

public final class AlarmPolicyRepositoryFixtures {

    private AlarmPolicyRepositoryFixtures() {}

    public static YamlAlarmPolicyRepository loadFromClasspath(String classpathLocation, AlarmSeverity defaultSeverity) {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAlarm().setPolicyLocation("classpath:" + classpathLocation);
        properties.getAlarm().setEnabled(true);
        properties.getAlarm().setDefaultSeverity(defaultSeverity.name());
        YamlAlarmPolicyRepository repository = new YamlAlarmPolicyRepository(properties);
        repository.load();
        return repository;
    }
}
