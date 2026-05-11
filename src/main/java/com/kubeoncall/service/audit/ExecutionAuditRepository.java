package com.kubeoncall.service.audit;

import com.kubeoncall.domain.audit.ExecutionAuditRecord;

import java.time.Instant;
import java.util.List;

public interface ExecutionAuditRepository {

    void save(ExecutionAuditRecord record);

    List<ExecutionAuditRecord> findAll();

    List<ExecutionAuditRecord> findRecent(int limit);

    void deleteBefore(Instant threshold);
}
