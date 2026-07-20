package com.kubeoncall.workflow;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.kubeoncall.domain.graph.NodeResult;

/** Runs the ordered checks that can complete an alarm before workflow nodes execute. */
@Service
public class AlertWorkflowPreflight {

    private final AlertWorkflowMemory workflowMemory;
    private final AlarmWorkflowRecoveryHandler recoveryHandler;
    private final AlarmWorkflowSuppressionHandler suppressionHandler;
    private final AlarmWorkflowDeduplicator deduplicator;

    public AlertWorkflowPreflight(
            AlertWorkflowMemory workflowMemory,
            AlarmWorkflowRecoveryHandler recoveryHandler,
            AlarmWorkflowSuppressionHandler suppressionHandler,
            AlarmWorkflowDeduplicator deduplicator) {
        this.workflowMemory = workflowMemory;
        this.recoveryHandler = recoveryHandler;
        this.suppressionHandler = suppressionHandler;
        this.deduplicator = deduplicator;
    }

    public Result process(AlarmEventPreparationService.PreparedAlarm preparedAlarm, Instant startedAt) {
        recoveryHandler.cancelPendingRecovery(preparedAlarm.event());
        AlertWorkflowMemory.Recall memoryRecall = workflowMemory.recall(preparedAlarm.event());
        suppressionHandler.recordSources(preparedAlarm.event());

        List<NodeResult> terminalResults = recoveryHandler
                .handle(preparedAlarm, memoryRecall, startedAt)
                .or(() -> suppressionHandler.handle(preparedAlarm, memoryRecall, startedAt))
                .or(() -> deduplicator.handle(preparedAlarm, memoryRecall, startedAt))
                .orElseGet(List::of);
        return new Result(preparedAlarm, memoryRecall, terminalResults);
    }

    public record Result(
            AlarmEventPreparationService.PreparedAlarm preparedAlarm,
            AlertWorkflowMemory.Recall memoryRecall,
            List<NodeResult> terminalResults) {

        public Result {
            terminalResults = List.copyOf(terminalResults);
        }

        public boolean hasTerminalResult() {
            return !terminalResults.isEmpty();
        }
    }
}
