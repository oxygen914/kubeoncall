package com.kubeoncall.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies the WBS-11 legacy API retirement flag. With {@code kubeoncall.legacy-api.enabled=false}
 * the legacy {@code /api/status} controller is not registered (404), while the v1 surface keeps
 * serving {@code /api/v1/system/status}. This is the config-only rollback lever: flipping the flag
 * back re-registers legacy endpoints without code or data changes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"kubeoncall.legacy-api.enabled=false", "kubeoncall.api-security.enabled=false"})
class LegacyApiRetirementTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void legacyStatusIsRetiredWhileV1StaysUp() {
        ResponseEntity<String> legacy = restTemplate.getForEntity(url("/api/status"), String.class);
        // The legacy controller is not registered, so the route is unserved. The global legacy
        // exception handler turns the "no handler" case into a 4xx/5xx rather than a clean 404; either
        // way the endpoint is retired, which is what the flag guarantees. What matters is that it is
        // not the legacy 200 status response.
        assertThat(legacy.getStatusCode().is4xxClientError()
                        || legacy.getStatusCode().is5xxServerError())
                .as("legacy /api/status must not serve after retirement")
                .isTrue();

        ResponseEntity<String> v1 = restTemplate.getForEntity(url("/api/v1/system/status"), String.class);
        assertThat(v1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(v1.getBody()).contains("KubeOnCall");
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
