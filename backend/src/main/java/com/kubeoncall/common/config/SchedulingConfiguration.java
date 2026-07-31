package com.kubeoncall.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables worker schedules for the long-running application, but not for one-shot migration Jobs. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "kubeoncall.migration-only", havingValue = "false", matchIfMissing = true)
public class SchedulingConfiguration {}
