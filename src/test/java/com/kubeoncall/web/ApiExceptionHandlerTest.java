package com.kubeoncall.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import com.kubeoncall.common.exception.ApprovalRequiredException;

class ApiExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void shouldExposeStableContractForInvalidRequest() throws Exception {
        mockMvc.perform(get("/invalid").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("fingerprint is required"))
                .andExpect(jsonPath("$.path").value("/invalid"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void shouldExposeApprovalContextWithoutLeakingUnexpectedFailureDetails() throws Exception {
        mockMvc.perform(get("/approval").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.details.executionId").value("execution-1"));

        mockMvc.perform(get("/unexpected").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    @Test
    void shouldMapResponseStatusExceptionsToTheStableErrorCode() throws Exception {
        mockMvc.perform(get("/missing").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("maintenance window not found"))
                .andExpect(jsonPath("$.path").value("/missing"));
    }

    @Controller
    private static class FailingController {

        @GetMapping("/invalid")
        @ResponseBody
        void invalid() {
            throw new IllegalArgumentException("fingerprint is required");
        }

        @GetMapping("/approval")
        @ResponseBody
        void approval() {
            throw new ApprovalRequiredException("execution-1", "Approval is required");
        }

        @GetMapping("/unexpected")
        @ResponseBody
        void unexpected() {
            throw new RuntimeException("database connection string is unavailable");
        }

        @GetMapping("/missing")
        @ResponseBody
        void missing() {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "maintenance window not found");
        }
    }
}
