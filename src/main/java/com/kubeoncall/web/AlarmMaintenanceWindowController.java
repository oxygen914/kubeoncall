package com.kubeoncall.web;

import com.kubeoncall.alarm.maintenance.AlarmMaintenanceWindow;
import com.kubeoncall.alarm.maintenance.AlarmMaintenanceWindowService;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.web.dto.AlarmMaintenanceWindowRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alarm-maintenance-windows")
public class AlarmMaintenanceWindowController {

    private final AlarmMaintenanceWindowService service;
    private final ExecutionAuditService auditService;

    public AlarmMaintenanceWindowController(AlarmMaintenanceWindowService service,
                                            ExecutionAuditService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    @PostMapping
    public AlarmMaintenanceWindow create(@RequestBody AlarmMaintenanceWindowRequest request) {
        Instant startedAt = Instant.now();
        try {
            if (request == null) {
                throw new IllegalArgumentException("request body is required");
            }
            AlarmMaintenanceWindow window = service.create(
                    request.startsAt(), request.endsAt(), request.matchers(), request.reason(),
                    request.createdBy(), request.approvedBy(), request.approvalReference());
            auditService.recordAlarmExecution(
                    "maintenance-window-create-" + window.id(), "MAINTENANCE_WINDOW_CREATED", false, true,
                    "Maintenance window created and approved", null,
                    List.of("alarm.maintenance.create"), startedAt,
                    Map.of("windowId", window.id(), "createdBy", window.createdBy(),
                            "approvedBy", window.approvedBy(), "approvalReference", window.approvalReference(),
                            "startsAt", window.startsAt().toString(), "endsAt", window.endsAt().toString(),
                            "matchers", window.matchers()));
            return window;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> revoke(@PathVariable String id) {
        Instant startedAt = Instant.now();
        boolean removed = service.revoke(id);
        auditService.recordAlarmExecution(
                "maintenance-window-revoke-" + id + "-" + startedAt.toEpochMilli(),
                removed ? "MAINTENANCE_WINDOW_REVOKED" : "MAINTENANCE_WINDOW_NOT_FOUND",
                true, false, removed ? "Maintenance window revoked" : "Maintenance window not found",
                null, List.of("alarm.maintenance.revoke"), startedAt, Map.of("windowId", id));
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "maintenance window not found");
        }
        return Map.of("id", id, "revoked", true);
    }
}
