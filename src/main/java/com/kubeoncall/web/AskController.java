package com.kubeoncall.web;

import com.kubeoncall.service.AskService;
import com.kubeoncall.web.dto.AskRequest;
import com.kubeoncall.web.dto.AskResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ask")
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @PostMapping
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        AskService.AskExecutionResult result = askService.handle(request.question(), request.sessionId());
        return new AskResponse(result.executionId(), result.status(), result.message(), result.sessionId());
    }
}
