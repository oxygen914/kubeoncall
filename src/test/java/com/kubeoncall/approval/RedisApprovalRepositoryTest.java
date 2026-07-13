package com.kubeoncall.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.approval.ApprovalDecision;
import com.kubeoncall.domain.approval.ApprovalRequest;
import com.kubeoncall.domain.task.TaskPlan;

class RedisApprovalRepositoryTest {

    @Test
    void shouldSaveAndLoadApprovalRequest() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getApproval().setCallbackTimeoutSeconds(600);

        RedisApprovalRepository repository = new RedisApprovalRepository(redisTemplate, objectMapper, properties);

        ApprovalRequest request = new ApprovalRequest(
                "exec-1",
                new TaskPlan("exec-1", "q", List.of(), Instant.now(), true),
                "task-1",
                "tester",
                ApprovalDecision.PENDING,
                Instant.now(),
                null,
                "comment",
                null,
                false,
                List.of("risk"));

        repository.save(request);

        verify(valueOperations).set(eq("approval-request:exec-1"), anyString(), any());

        String serialized = objectMapper.writeValueAsString(request);
        when(valueOperations.get("approval-request:exec-1")).thenReturn(serialized);
        Optional<ApprovalRequest> loaded = repository.findByExecutionId("exec-1");

        assertTrue(loaded.isPresent());
        assertEquals("exec-1", loaded.get().executionId());
        assertEquals("task-1", loaded.get().taskId());
    }
}
