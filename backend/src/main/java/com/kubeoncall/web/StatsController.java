package com.kubeoncall.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.domain.audit.ExecutionStats;
import com.kubeoncall.service.ExecutionAuditService;

@LegacyApiController
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final ExecutionAuditService executionAuditService;

    public StatsController(ExecutionAuditService executionAuditService) {
        this.executionAuditService = executionAuditService;
    }

    @GetMapping
    public ExecutionStats stats() {
        return executionAuditService.stats();
    }
}
