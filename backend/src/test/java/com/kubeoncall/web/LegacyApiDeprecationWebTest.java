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

/** Verifies the interceptor is registered on a real legacy controller, not just unit-wired. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "kubeoncall.api-security.enabled=false")
class LegacyApiDeprecationWebTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void legacyStatusCarriesRetirementHeaders() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/status"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Deprecation")).isEqualTo("@1782864000");
        assertThat(response.getHeaders().getFirst("Sunset")).isEqualTo("Thu, 31 Dec 2026 23:59:59 GMT");
        assertThat(response.getHeaders().getFirst("Link"))
                .isEqualTo("</api/v1/system/status>; rel=\"successor-version\"");
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
