package com.kubeoncall.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/status")
public class StatusController {

    @GetMapping
    public Map<String, Object> status() {
        return Map.of(
                "service", "KubeOnCall",
                "status", "UP"
        );
    }
}
