package com.kubeoncall.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.web.dto.ServiceStatusResponse;

@LegacyApiController
@RestController
@RequestMapping("/api/status")
public class StatusController {

    @GetMapping
    public ServiceStatusResponse status() {
        return new ServiceStatusResponse("KubeOnCall", "UP");
    }
}
