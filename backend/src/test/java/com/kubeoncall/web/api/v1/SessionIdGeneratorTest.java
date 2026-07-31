package com.kubeoncall.web.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.kubeoncall.identity.SessionIdGenerator;
import com.kubeoncall.identity.SessionRecord;

class SessionIdGeneratorTest {

    @Test
    void generatedIdsAreUniqueAndPrefixed() {
        String a = SessionIdGenerator.generate();
        String b = SessionIdGenerator.generate();
        assertThat(a).startsWith("koc_");
        assertThat(b).startsWith("koc_");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void normalizeRejectsMalformedIds() {
        assertThat(SessionIdGenerator.normalize(null)).isNull();
        assertThat(SessionIdGenerator.normalize("")).isNull();
        assertThat(SessionIdGenerator.normalize("koc_")).isNull();
        assertThat(SessionIdGenerator.normalize("ses_short")).isNull();
        assertThat(SessionIdGenerator.normalize("koc_!!invalid!!")).isNull();
    }

    @Test
    void generatedIdRoundTripsThroughNormalize() {
        String id = SessionIdGenerator.generate();
        assertThat(SessionIdGenerator.normalize(id)).isEqualTo(id);
    }

    @Test
    void sessionExpiresByIdleAndAbsoluteTimeout() {
        Instant issued = Instant.parse("2026-07-20T10:00:00Z");
        SessionRecord record = new SessionRecord(
                1L, "usr_1", "alice", "Alice", 1L, issued, issued, issued.plusSeconds(3600), "secret");
        assertThat(record.isExpired(issued.plusSeconds(60), 1800)).isFalse();
        assertThat(record.isExpired(issued.plusSeconds(4000), 1800)).isTrue(); // absolute
        assertThat(record.isExpired(issued.plusSeconds(2000), 1800)).isTrue(); // idle
    }

    @Test
    void touchAdvancesLastAccessAndShrinksExpiryToIdleWhenCloser() {
        Instant issued = Instant.parse("2026-07-20T10:00:00Z");
        SessionRecord record = new SessionRecord(
                1L, "usr_1", "alice", "Alice", 1L, issued, issued, issued.plusSeconds(3600), "secret");
        Instant now = issued.plusSeconds(60);
        SessionRecord touched = record.touch(now, 1800, 28800);
        assertThat(touched.lastAccessAt()).isEqualTo(now);
        assertThat(Duration.between(touched.lastAccessAt(), touched.expiresAt()).toSeconds())
                .isEqualTo(1800);
    }
}
