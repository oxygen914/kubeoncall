package com.kubeoncall.web;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.alarm.correlation.ChangeCorrelation;
import com.kubeoncall.alarm.correlation.ChangeCorrelationService;
import com.kubeoncall.alarm.correlation.ChangeEvent;
import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.web.dto.AlarmRequest;

/** Records deployment changes and exposes deterministic correlation for troubleshooting and replay. */
@RestController
@RequestMapping("/api/change-events")
public class ChangeEventController {

    private final ChangeCorrelationService correlationService;
    private final AlarmNormalizer alarmNormalizer;

    public ChangeEventController(ChangeCorrelationService correlationService, AlarmNormalizer alarmNormalizer) {
        this.correlationService = correlationService;
        this.alarmNormalizer = alarmNormalizer;
    }

    @PostMapping
    public ChangeEvent record(@RequestBody ChangeEvent event) {
        correlationService.record(event);
        return event;
    }

    @PostMapping("/correlations")
    public List<ChangeCorrelation> correlate(@RequestBody AlarmRequest alarm) {
        return correlationService.findRelatedChanges(alarmNormalizer.normalize(alarm));
    }
}
