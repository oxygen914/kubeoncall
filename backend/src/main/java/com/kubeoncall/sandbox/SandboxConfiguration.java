package com.kubeoncall.sandbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kubeoncall.sandbox.domain.SandboxStateMachine;
import com.kubeoncall.sandbox.policy.SandboxExecutionPolicy;

/**
 * Registers the stateless sandbox domain and policy beans. These carry no state of their own — the
 * {@link SandboxStateMachine} is the single authority on run/cleanup transition legality and the
 * {@link SandboxExecutionPolicy} the single approval/risk evaluator — so a singleton instance is
 * safe for concurrent reconcilers. The MySQL-backed {@link SandboxRunRepository} is component-scanned
 * on its own conditional annotation.
 */
@Configuration
class SandboxConfiguration {

    @Bean
    SandboxStateMachine sandboxStateMachine() {
        return new SandboxStateMachine();
    }

    @Bean
    SandboxExecutionPolicy sandboxExecutionPolicy() {
        return new SandboxExecutionPolicy();
    }
}
