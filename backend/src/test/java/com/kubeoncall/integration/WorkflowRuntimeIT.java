package com.kubeoncall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.agent.executor.OperationClosureFactRepository;
import com.kubeoncall.approval.mysql.ApprovalRequestRecord;
import com.kubeoncall.approval.mysql.MySqlApprovalRepository;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.graph.PauseMetadata;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.evidence.AiConclusion;
import com.kubeoncall.evidence.ConclusionRepository;
import com.kubeoncall.evidence.ConfidenceAssessment;
import com.kubeoncall.evidence.EvidenceCollectionStatus;
import com.kubeoncall.evidence.EvidenceItem;
import com.kubeoncall.evidence.EvidenceRepository;
import com.kubeoncall.evidence.EvidenceResource;
import com.kubeoncall.evidence.EvidenceType;
import com.kubeoncall.evidence.EvidenceWindow;
import com.kubeoncall.evidence.RecommendedAction;
import com.kubeoncall.evidence.SopEvidenceReference;
import com.kubeoncall.idempotency.IdempotencyService;
import com.kubeoncall.service.AskService;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;
import com.kubeoncall.task.worker.AsyncTaskContext;
import com.kubeoncall.task.worker.AsyncTaskHandler;
import com.kubeoncall.task.worker.AsyncTaskHandlerRegistry;
import com.kubeoncall.task.worker.AsyncTaskWorker;
import com.kubeoncall.workflow.execution.WorkflowExecutionRecord;
import com.kubeoncall.workflow.execution.WorkflowExecutionRepository;
import com.kubeoncall.workflow.runtime.ApprovalDecisionCommandService;
import com.kubeoncall.workflow.runtime.AsyncCommandResult;
import com.kubeoncall.workflow.runtime.WorkflowSubmissionService;
import com.kubeoncall.workflow.runtime.WorkflowTaskResultCoordinator;

/** Real-MySQL WBS-7 lifecycle: submit, pause, decide, resume and fenced terminal persistence. */
@Testcontainers
class WorkflowRuntimeIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("kubeoncall")
            .withUsername("kubeoncall")
            .withPassword("test-password")
            .withReuse(false);

    private static JdbcTemplate jdbcTemplate;
    private static WorkflowExecutionRepository executions;
    private static MySqlApprovalRepository approvals;
    private static AsyncTaskRepository tasks;
    private static EvidenceRepository evidenceRepository;
    private static ConclusionRepository conclusionRepository;
    private static OperationClosureFactRepository operationClosureFacts;
    private static WorkflowSubmissionService submissionService;
    private static ApprovalDecisionCommandService decisionService;
    private static WorkflowTaskResultCoordinator coordinator;

    @BeforeAll
    static void setUp() {
        DataSource dataSource = DataSourceBuilder.create()
                .url(MYSQL.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false")
                .username(MYSQL.getUsername())
                .password(MYSQL.getPassword())
                .build();
        jdbcTemplate = new JdbcTemplate(dataSource);
        org.flywaydb.core.Flyway.configure()
                .dataSource(
                        MYSQL.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false",
                        MYSQL.getUsername(),
                        MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbcTemplate.update("""
                INSERT INTO koc_user
                  (id, public_id, username, username_normalized, display_name, password_hash, status)
                VALUES (1, 'usr_workflow_it', 'workflow-it', 'workflow-it',
                        'Workflow IT', 'not-used-by-this-test', 'ACTIVE')
                """);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        executions = new WorkflowExecutionRepository(jdbcTemplate, true);
        approvals = new MySqlApprovalRepository(jdbcTemplate, objectMapper, true);
        evidenceRepository = new EvidenceRepository(jdbcTemplate, objectMapper);
        conclusionRepository = new ConclusionRepository(jdbcTemplate, objectMapper);
        operationClosureFacts = new OperationClosureFactRepository(jdbcTemplate, objectMapper);
        AsyncTaskRepository taskTarget = new AsyncTaskRepository(jdbcTemplate, objectMapper, true);
        PlatformTransactionManager transactionManager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        tasks = transactional(taskTarget, transactionManager);

        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbcProvider =
                (ObjectProvider<JdbcTemplate>) new SingletonObjectProvider<>(jdbcTemplate);
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkflowExecutionRepository> executionProvider =
                (ObjectProvider<WorkflowExecutionRepository>) new SingletonObjectProvider<>(executions);
        @SuppressWarnings("unchecked")
        ObjectProvider<MySqlApprovalRepository> approvalProvider =
                (ObjectProvider<MySqlApprovalRepository>) new SingletonObjectProvider<>(approvals);
        @SuppressWarnings("unchecked")
        ObjectProvider<AsyncTaskRepository> taskProvider =
                (ObjectProvider<AsyncTaskRepository>) new SingletonObjectProvider<>(tasks);
        OperationAuditWriter auditWriter = new OperationAuditWriter(jdbcProvider, objectMapper);
        OutboxWriter outboxWriter = new OutboxWriter(jdbcProvider, objectMapper);
        IdempotencyService idempotency = new IdempotencyService(jdbcProvider, objectMapper, Duration.ofHours(1));

        submissionService = transactional(
                new WorkflowSubmissionService(
                        executionProvider, taskProvider, auditWriter, outboxWriter, idempotency, objectMapper),
                transactionManager);
        decisionService = transactional(
                new ApprovalDecisionCommandService(
                        approvalProvider,
                        executionProvider,
                        taskProvider,
                        auditWriter,
                        outboxWriter,
                        idempotency,
                        objectMapper),
                transactionManager);
        coordinator = transactional(
                new WorkflowTaskResultCoordinator(
                        executions, approvals, tasks, auditWriter, outboxWriter, new KubeOnCallProperties(), 60),
                transactionManager);
    }

    @Test
    void persistsVersionedOperationClosureAndEscalationFacts() {
        Instant startedAt = Instant.parse("2026-07-30T08:00:00Z");
        operationClosureFacts.upsertClosure(
                "op-workflow-it-1",
                "exe-workflow-it-closure",
                "task-workflow-it-closure",
                "kubernetes",
                "scaleWorkload",
                "prod/payment-api",
                "PREPARED",
                Map.of("operationId", "op-workflow-it-1", "apiKey", "must-not-be-stored"),
                null,
                startedAt,
                null);
        operationClosureFacts.upsertClosure(
                "op-workflow-it-1",
                "exe-workflow-it-closure",
                "task-workflow-it-closure",
                "kubernetes",
                "scaleWorkload",
                "prod/payment-api",
                "VERIFIED",
                Map.of("operationId", "op-workflow-it-1", "status", "VERIFIED"),
                null,
                startedAt,
                startedAt.plusSeconds(30));

        OperationClosureFactRepository.ClosureFact closure = operationClosureFacts
                .findLatestClosure("exe-workflow-it-closure")
                .orElseThrow();
        assertThat(closure.phase()).isEqualTo("VERIFIED");
        assertThat(closure.version()).isEqualTo(1);
        assertThat(closure.finishedAt()).isEqualTo(startedAt.plusSeconds(30));
        assertThat(String.valueOf(closure.details())).doesNotContain("must-not-be-stored");

        operationClosureFacts.upsertEscalation(
                "op-workflow-it-1",
                "exe-workflow-it-closure",
                "PENDING_MANUAL",
                "HIGH",
                "Rollback verification needs an operator",
                Map.of("closureStatus", "ROLLED_BACK"),
                null);

        assertThat(operationClosureFacts.findEscalation("op-workflow-it-1"))
                .get()
                .satisfies(escalation -> {
                    assertThat(escalation.status()).isEqualTo("PENDING_MANUAL");
                    assertThat(escalation.executionId()).isEqualTo("exe-workflow-it-closure");
                });
    }

    @Test
    void submitPauseApproveResumeAndFenceTerminalResult() {
        String key = "workflow-it-key-0001";
        IdempotencyService.IdempotencyScope createScope =
                new IdempotencyService.IdempotencyScope("USER", "usr_workflow_it", "POST:/api/v1/executions");
        WorkflowSubmissionService.SubmitAskCommand submit = new WorkflowSubmissionService.SubmitAskCommand(
                "restart payment-service",
                "session-it",
                null,
                1L,
                "usr_workflow_it",
                "Workflow IT",
                "req_workflow_create",
                "trace_workflow",
                "127.0.0.1",
                "integration-test");

        AsyncCommandResult accepted = submissionService.submitAsk(submit, createScope, key, "ask|restart");
        AsyncCommandResult replay = submissionService.submitAsk(submit, createScope, key, "ask|restart");

        assertThat(accepted.action()).isEqualTo(AsyncCommandResult.Action.EXECUTED);
        assertThat(replay.action()).isEqualTo(AsyncCommandResult.Action.REPLAY);
        assertThat(replay.data()).isEqualTo(accepted.data());
        String executionId = String.valueOf(accepted.data().get("executionId"));
        String askTaskId = String.valueOf(accepted.data().get("taskId"));
        assertThat(executions.findByPublicId(executionId))
                .get()
                .extracting(WorkflowExecutionRecord::status)
                .isEqualTo("PENDING");

        AsyncTaskWorker askWorker = worker("owner-ask", Set.of("ASK_EXECUTION"), new AsyncTaskHandler() {
            @Override
            public String taskType() {
                return "ASK_EXECUTION";
            }

            @Override
            public HandlerResult handle(AsyncTaskContext context) {
                coordinator.markRunning(context);
                AskService.AskExecutionResult paused = pausedResult(executionId);
                return new HandlerResult(coordinator
                        .finalizeResult(context, paused, "integration-pause")
                        .taskResult());
            }
        });
        AsyncTaskWorker.RunResult pausedRun = askWorker.runOnce();

        assertThat(pausedRun.outcome()).isEqualTo(AsyncTaskWorker.Outcome.SUCCEEDED);
        assertThat(pausedRun.taskPublicId()).isEqualTo(askTaskId);
        WorkflowExecutionRecord waiting = executions.findByPublicId(executionId).orElseThrow();
        assertThat(waiting.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(waiting.finishedAt()).isNull();
        ApprovalRequestRecord approval = approvals
                .list(new MySqlApprovalRepository.ApprovalQuery(1, 10, List.of("PENDING"), List.of(), executionId))
                .rows()
                .get(0);
        assertThat(approval.riskLevel()).isEqualTo("HIGH");
        assertThat(tasks.findByPublicId(askTaskId))
                .get()
                .extracting(AsyncTaskRecord::status)
                .isEqualTo("SUCCEEDED");

        String decisionKey = "workflow-it-decision-0001";
        IdempotencyService.IdempotencyScope decisionScope = new IdempotencyService.IdempotencyScope(
                "USER", "usr_workflow_it", "POST:/api/v1/approvals/" + approval.publicId() + "/decisions");
        AsyncCommandResult decision = decisionService.decide(
                new ApprovalDecisionCommandService.DecisionCommand(
                        approval.publicId(),
                        approval.version(),
                        "APPROVED",
                        "reviewed",
                        1L,
                        "usr_workflow_it",
                        "Workflow IT",
                        Instant.now(),
                        "req_workflow_decide",
                        "trace_workflow",
                        "127.0.0.1",
                        "integration-test"),
                decisionScope,
                decisionKey,
                "approve|" + approval.publicId());

        assertThat(decision.action()).isEqualTo(AsyncCommandResult.Action.EXECUTED);
        String resumeTaskId = String.valueOf(decision.data().get("taskId"));
        assertThat(executions.findByPublicId(executionId))
                .get()
                .extracting(WorkflowExecutionRecord::status)
                .isEqualTo("APPROVED");
        assertThat(approvals.findByPublicId(approval.publicId()))
                .get()
                .extracting(ApprovalRequestRecord::status)
                .isEqualTo("APPROVED");

        AsyncTaskWorker resumeWorker = worker("owner-resume", Set.of("APPROVAL_RESUME"), new AsyncTaskHandler() {
            @Override
            public String taskType() {
                return "APPROVAL_RESUME";
            }

            @Override
            public HandlerResult handle(AsyncTaskContext context) {
                coordinator.markResuming(context);
                AskService.AskExecutionResult succeeded = successResult(executionId);
                return new HandlerResult(coordinator
                        .finalizeResult(context, succeeded, "integration-resume")
                        .taskResult());
            }
        });
        AsyncTaskWorker.RunResult resumedRun = resumeWorker.runOnce();

        assertThat(resumedRun.outcome()).isEqualTo(AsyncTaskWorker.Outcome.SUCCEEDED);
        assertThat(resumedRun.taskPublicId()).isEqualTo(resumeTaskId);
        WorkflowExecutionRecord finished =
                executions.findByPublicId(executionId).orElseThrow();
        assertThat(finished.status()).isEqualTo("SUCCEEDED");
        assertThat(finished.finishedAt()).isNotNull();
        assertThat(executions.listNodes(executionId)).hasSize(2);
        AsyncTaskRecord completedResume = tasks.findByPublicId(resumeTaskId).orElseThrow();
        assertThat(completedResume.status()).isEqualTo("SUCCEEDED");
        assertThat(tasks.complete(
                        resumeTaskId,
                        "owner-resume",
                        completedResume.fencingToken(),
                        Map.of("stale", true),
                        Instant.now()))
                .isFalse();

        assertThat(count("SELECT COUNT(*) FROM koc_operation_audit WHERE resource_public_id = ?", executionId))
                .isGreaterThanOrEqualTo(3L);
        assertThat(count("SELECT COUNT(*) FROM koc_outbox_event WHERE aggregate_public_id = ?", executionId))
                .isGreaterThanOrEqualTo(4L);
    }

    @Test
    void expiredWorkerLeaseIsReclaimedAndTheOldOwnerCannotComplete() {
        Instant claimedAt = Instant.parse("2026-07-22T01:00:00Z");
        AsyncTaskRecord created = tasks.create(new AsyncTaskRepository.CreateTask(
                "tsk_reclaim_after_restart",
                "KNOWLEDGE_IMPORT",
                "KNOWLEDGE_IMPORT",
                "imp_reclaim_after_restart",
                "reclaim-after-restart",
                "queued",
                Map.of("source", "integration-test"),
                3,
                claimedAt,
                "req_reclaim_after_restart",
                null));

        AsyncTaskRecord oldOwner = tasks.claimNext(
                        "worker-before-restart", claimedAt, Duration.ofSeconds(30), Set.of("KNOWLEDGE_IMPORT"))
                .orElseThrow();
        AsyncTaskRecord reclaimed = tasks.claimNext(
                        "worker-after-restart",
                        claimedAt.plusSeconds(31),
                        Duration.ofSeconds(30),
                        Set.of("KNOWLEDGE_IMPORT"))
                .orElseThrow();

        assertThat(reclaimed.publicId()).isEqualTo(created.publicId());
        assertThat(reclaimed.attempt()).isEqualTo(oldOwner.attempt() + 1);
        assertThat(reclaimed.fencingToken()).isEqualTo(oldOwner.fencingToken() + 1);
        assertThat(tasks.complete(
                        created.publicId(),
                        oldOwner.ownerToken(),
                        oldOwner.fencingToken(),
                        Map.of("stale", true),
                        claimedAt.plusSeconds(31)))
                .isFalse();
        assertThat(tasks.complete(
                        created.publicId(),
                        reclaimed.ownerToken(),
                        reclaimed.fencingToken(),
                        Map.of("reclaimed", true),
                        claimedAt.plusSeconds(31)))
                .isTrue();
        assertThat(tasks.findByPublicId(created.publicId()).orElseThrow())
                .extracting(AsyncTaskRecord::status)
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void evidenceAndConclusionsSurviveIdempotentMySqlProjection() {
        String executionId = "exe_evidence_it";
        executions.create(new WorkflowExecutionRepository.CreateExecution(
                executionId,
                "ASK",
                "ASK",
                null,
                "evidence-it",
                "RUNNING",
                "LOW",
                "diagnose pending pod",
                "USER",
                1L,
                "session-evidence-it",
                "req-evidence-it",
                "trace-evidence-it",
                "graph:evidence-it",
                Instant.parse("2026-07-29T10:00:00Z")));
        Instant observedAt = Instant.parse("2026-07-29T10:00:10Z");
        EvidenceItem evidence = new EvidenceItem(
                "evd_evidence_it",
                executionId,
                EvidenceType.K8S_EVENT,
                "kubernetes-api",
                "test-01",
                "payments",
                new EvidenceResource("Pod", "payment-api-1", "pod-uid-1"),
                observedAt,
                new EvidenceWindow(observedAt.minusSeconds(60), observedAt),
                "Pod scheduling failed",
                "0/3 nodes are available: Insufficient memory",
                Map.of("sequence", "3912"),
                8,
                true,
                false,
                "sha256:evidence-it",
                EvidenceCollectionStatus.SUCCEEDED,
                "",
                "",
                Map.of("reason", "FailedScheduling"));
        AiConclusion conclusion = new AiConclusion(
                "con_evidence_it",
                executionId,
                "Pod Pending is caused by insufficient schedulable memory",
                "P2",
                "SUPPORTED",
                List.of(evidence.evidenceId()),
                List.of(new SopEvidenceReference("pod-pending-triage", "1.3.0", "runbook", "Scheduling")),
                new ConfidenceAssessment(0.91, "HIGH", Map.of("directEvidence", 1.0)),
                Map.of("mode", "REAL_MODEL", "model", "qwen-plus"),
                new RecommendedAction("PATCH_CONFIG", true, Map.of("resource", "payment-api-1")));

        evidenceRepository.upsert(executionId, evidence);
        evidenceRepository.upsert(executionId, evidence);
        conclusionRepository.upsert(executionId, conclusion);
        conclusionRepository.upsert(executionId, conclusion);

        assertThat(evidenceRepository.list(executionId)).singleElement().satisfies(stored -> {
            assertThat(stored.evidenceId()).isEqualTo(evidence.evidenceId());
            assertThat(stored.resource().uid()).isEqualTo("pod-uid-1");
            assertThat(stored.collectionStatus()).isEqualTo(EvidenceCollectionStatus.SUCCEEDED);
        });
        assertThat(conclusionRepository.list(executionId)).singleElement().satisfies(stored -> {
            assertThat(stored.conclusionId()).isEqualTo(conclusion.conclusionId());
            assertThat(stored.evidenceRefs()).containsExactly(evidence.evidenceId());
            assertThat(stored.confidence().label()).isEqualTo("HIGH");
        });
    }

    private static AsyncTaskWorker worker(String owner, Set<String> types, AsyncTaskHandler handler) {
        return new AsyncTaskWorker(
                tasks,
                new AsyncTaskHandlerRegistry(List.of(handler)),
                Clock.systemUTC(),
                owner,
                Duration.ofSeconds(60),
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                types);
    }

    private static AskService.AskExecutionResult pausedResult(String executionId) {
        Task task = new Task(
                "task-restart",
                "restart payment-service",
                TaskType.RESTART_SERVICE,
                RiskLevel.HIGH,
                "payment-service",
                Map.of(),
                null);
        Instant pausedAt = Instant.now();
        PauseMetadata pause = new PauseMetadata(
                "Awaiting human approval",
                "verifierApprovalNode",
                pausedAt,
                task.taskId(),
                task.taskType().name(),
                task.target(),
                task.riskLevel().name(),
                "OPERATOR",
                List.of("high-risk restart"),
                Map.of("target", task.target()));
        Map<String, Object> details = Map.of(
                "currentTask",
                task,
                "approval",
                Map.of("pause", pause),
                "nodeResults",
                List.of(new NodeResult("verifierApprovalNode", NodeStatus.WAITING, "Approval created", Map.of())));
        return new AskService.AskExecutionResult(executionId, "PAUSED", "Approval required", "session-it", details);
    }

    private static AskService.AskExecutionResult successResult(String executionId) {
        Map<String, Object> details = Map.of(
                "nodeResults",
                List.of(new NodeResult("executorExecuteNode", NodeStatus.SUCCESS, "Restart completed", Map.of())));
        return new AskService.AskExecutionResult(executionId, "SUCCESS", "Restart completed", "session-it", details);
    }

    private static long count(String sql, String resourceId) {
        return jdbcTemplate.queryForObject(sql, Long.class, resourceId);
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, PlatformTransactionManager transactionManager) {
        TransactionProxyFactoryBean proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(target);
        proxy.setProxyTargetClass(true);
        proxy.setTransactionManager(transactionManager);
        java.util.Properties attributes = new java.util.Properties();
        attributes.setProperty("*", "PROPAGATION_REQUIRED");
        proxy.setTransactionAttributes(attributes);
        proxy.afterPropertiesSet();
        return (T) proxy.getObject();
    }

    private static final class SingletonObjectProvider<T> implements ObjectProvider<T> {

        private final T instance;

        private SingletonObjectProvider(T instance) {
            this.instance = instance;
        }

        @Override
        public T getObject(Object... args) {
            return instance;
        }

        @Override
        public T getObject() {
            return instance;
        }

        @Override
        public T getIfAvailable() {
            return instance;
        }

        @Override
        public T getIfUnique() {
            return instance;
        }
    }
}
