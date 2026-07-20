package com.kubeoncall.alarm.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;

/** Builds a delivery identity that is stable across Alertmanager webhook retries. */
@Service
public class AlarmDeliveryIdentityService {

    public String deliveryKey(NormalizedAlarmEvent event, Instant endsAt) {
        String fingerprint = event.fingerprint() == null ? "" : event.fingerprint();
        String status = event.status() == null ? "FIRING" : event.status().name();
        return sha256(fingerprint + "|" + status + "|" + instant(event.occurredAt()) + "|" + instant(endsAt));
    }

    private String instant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to calculate alarm delivery identity", ex);
        }
    }
}
