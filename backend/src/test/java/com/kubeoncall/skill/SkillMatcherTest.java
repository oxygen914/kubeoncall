package com.kubeoncall.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.kubeoncall.common.config.KubeOnCallProperties;

class SkillMatcherTest {

    private static final Set<String> P0_SKILLS = Set.of(
            "pod-crashloop-triage",
            "pod-oom-triage",
            "cluster-scheduling-capacity-triage",
            "node-cpu-pressure-triage",
            "node-memory-pressure-triage");

    @ParameterizedTest(name = "{0}")
    @MethodSource("matchCases")
    void shouldMatchCatalogCases(MatchCase matchCase) {
        Catalog catalog = catalog();

        SkillMatcher.MatchResult result =
                catalog.matcher().matchResult(matchCase.request(), matchCase.context(), catalog.skills());
        List<String> ids =
                result.matches().stream().map(match -> match.skill().id()).toList();

        assertFalse(ids.isEmpty(), matchCase.id());
        assertEquals(matchCase.expectedPrimarySkill(), ids.get(0), matchCase.id());
        assertTrue(ids.containsAll(matchCase.expectedSupportingSkills()), matchCase.id());
        assertTrue(
                matchCase.forbiddenSkills().stream().noneMatch(ids::contains),
                () -> matchCase.id() + " activated forbidden skills " + ids);
        if (matchCase.context().containsKey("alertName")) {
            assertEquals(1, ids.size(), matchCase.id() + " must have one canonical primary skill");
        }
    }

    @Test
    void datasetShouldMeetPositiveNegativeAndCollisionCoverage() {
        List<MatchCase> cases = cases();
        for (String skillId : P0_SKILLS) {
            assertTrue(
                    cases.stream()
                                    .filter(item -> skillId.equals(item.expectedPrimarySkill()))
                                    .count()
                            >= 3,
                    skillId + " requires at least three positive cases");
            assertTrue(
                    cases.stream()
                                    .filter(item -> item.forbiddenSkills().contains(skillId))
                                    .count()
                            >= 3,
                    skillId + " requires at least three negative cases");
            assertTrue(
                    cases.stream()
                            .filter(item -> skillId.equals(item.expectedPrimarySkill()))
                            .anyMatch(item -> item.context().containsKey("alertName")
                                    && item.forbiddenSkills().stream().anyMatch(P0_SKILLS::contains)),
                    skillId + " requires a collision case");
        }
    }

    @Test
    void shouldHardFilterKnownResourceTypeAndRejectMutationTask() {
        Catalog catalog = catalog();

        List<Skill> wrongResource = catalog.matcher()
                .match("Pod OOMKilled", Map.of("resourceType", "NODE", "taskType", "QUERY_METRICS"), catalog.skills());
        List<Skill> mutation = catalog.matcher()
                .match(
                        "Please restart the service after Pod OOMKilled",
                        Map.of("resourceType", "POD"),
                        catalog.skills());

        assertTrue(wrongResource.stream().noneMatch(skill -> "pod-oom-triage".equals(skill.id())));
        assertTrue(mutation.stream().noneMatch(skill -> "pod-oom-triage".equals(skill.id())));
    }

    @Test
    void requestedSkillStillRequiresDomainSignal() {
        Catalog catalog = catalog();
        Skill podOom = catalog.skills().stream()
                .filter(skill -> "pod-oom-triage".equals(skill.id()))
                .findFirst()
                .orElseThrow();

        assertFalse(catalog.matcher()
                .canActivateRequested(
                        "inspect a Kubernetes Pod",
                        Map.of("resourceType", "POD", "taskType", "QUERY_METRICS"),
                        podOom));
        assertTrue(catalog.matcher()
                .canActivateRequested(
                        "inspect this Pod OOM", Map.of("resourceType", "POD", "taskType", "QUERY_METRICS"), podOom));
    }

    static Stream<MatchCase> matchCases() {
        return cases().stream();
    }

    private static List<MatchCase> cases() {
        try (InputStream input =
                SkillMatcherTest.class.getClassLoader().getResourceAsStream("skills/skill-match-cases.yml")) {
            if (input == null) {
                throw new AssertionError("skills/skill-match-cases.yml not found");
            }
            CaseFile file = new ObjectMapper(new YAMLFactory()).readValue(input, CaseFile.class);
            return file.cases();
        } catch (Exception ex) {
            throw new AssertionError("Unable to load Skill matcher cases", ex);
        }
    }

    private static Catalog catalog() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getSkill().setProjectLocation("");
        SkillStateStore stateStore = mock(SkillStateStore.class);
        when(stateStore.disabledIds()).thenReturn(Set.of());
        SkillRegistry registry = new SkillRegistry(properties, new SkillFrontmatterParser(), stateStore);
        registry.reload();
        return new Catalog(new SkillMatcher(properties), registry.all());
    }

    private record Catalog(SkillMatcher matcher, List<Skill> skills) {}

    private record CaseFile(List<MatchCase> cases) {}

    private record MatchCase(
            String id,
            String request,
            Map<String, Object> context,
            String expectedPrimarySkill,
            List<String> expectedSupportingSkills,
            List<String> forbiddenSkills) {

        private MatchCase {
            context = context == null ? Map.of() : Map.copyOf(context);
            expectedSupportingSkills =
                    expectedSupportingSkills == null ? List.of() : List.copyOf(expectedSupportingSkills);
            forbiddenSkills = forbiddenSkills == null ? List.of() : List.copyOf(forbiddenSkills);
        }

        @Override
        public String toString() {
            return id;
        }
    }
}
