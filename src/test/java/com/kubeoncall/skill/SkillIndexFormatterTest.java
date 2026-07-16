package com.kubeoncall.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.task.RiskLevel;

class SkillIndexFormatterTest {

    @Test
    void shouldBoundSkillCountDescriptionAndTotalBytes() {
        List<Skill> skills = java.util.stream.IntStream.range(0, 25)
                .mapToObj(index -> skill("skill-" + index, "中".repeat(800)))
                .toList();

        String formatted = new SkillIndexFormatter().format(skills);

        assertTrue(formatted.getBytes(StandardCharsets.UTF_8).length <= SkillIndexFormatter.MAX_INDEX_BYTES);
        assertTrue(formatted.contains("skill-0"));
        assertFalse(formatted.contains("skill-20"));
    }

    private Skill skill(String id, String description) {
        return new Skill(
                id,
                id,
                "v1",
                SkillSource.BUILTIN,
                "classpath:" + id,
                description,
                List.of("trigger"),
                List.of(),
                List.of(),
                RiskLevel.LOW,
                List.of(),
                "body",
                Map.of());
    }
}
