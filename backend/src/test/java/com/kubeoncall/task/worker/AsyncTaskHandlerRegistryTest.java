package com.kubeoncall.task.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class AsyncTaskHandlerRegistryTest {

    @Test
    void rejectsDuplicateTaskTypes() {
        AsyncTaskHandler first = mock(AsyncTaskHandler.class);
        AsyncTaskHandler second = mock(AsyncTaskHandler.class);
        when(first.taskType()).thenReturn("ASK_EXECUTION");
        when(second.taskType()).thenReturn("ASK_EXECUTION");

        assertThatThrownBy(() -> new AsyncTaskHandlerRegistry(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate async task handler for task type: ASK_EXECUTION");
    }
}
