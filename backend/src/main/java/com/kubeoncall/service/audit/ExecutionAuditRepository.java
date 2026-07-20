package com.kubeoncall.service.audit;

import java.time.Instant;
import java.util.List;

import com.kubeoncall.domain.audit.ExecutionAuditRecord;

public interface ExecutionAuditRepository {

    void save(ExecutionAuditRecord record);

    List<ExecutionAuditRecord> findAll();

    List<ExecutionAuditRecord> findRecent(int limit);

    void deleteBefore(Instant threshold);
}
