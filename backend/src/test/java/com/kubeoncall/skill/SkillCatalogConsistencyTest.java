package com.kubeoncall.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.policy.YamlAlarmPolicyRepository;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.memory.TokenBudget;
import com.kubeoncall.tool.AgentToolCatalog;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.alerting.AlertmanagerToolExecutor;
import com.kubeoncall.tool.k8s.KubernetesToolExecutor;
import com.kubeoncall.tool.mcp.McpToolRegistry;
import com.kubeoncall.tool.monitoring.PrometheusToolExecutor;

class SkillCatalogConsistencyTest {

    private static final Path SKILL_ROOT = Path.of("src/main/resources/skills");
    private static final Set<String> P0_SKILLS = Set.of(
            "pod-crashloop-triage",
            "pod-oom-triage",
            "cluster-scheduling-capacity-triage",
            "node-cpu-pressure-triage",
            "node-memory-pressure-triage");
    private static final Set<String> SKILL_REQUIRED_ALERTS = Set.of(
            "PodCrashLoopBackOffP1",
            "PodOOMKilledP1",
            "ClusterPendingPodsP1",
            "HostHighCpuUsageP1",
            "HostHighCpuUsageP0",
            "HostMemoryPressureP1",
            "HostMemoryPressureP0",
            "NodeDown",
            "NodeCPUHigh",
            "NodeMemoryLow",
            "NodeDiskHigh",
            "NodeInodeHigh",
            "NodeConntrackPressure",
            "NodeFilesystemReadOnly",
            "NodeNotReady",
            "PodPendingTooLong");

    @Test
    void catalogShouldHaveStableDirectoriesSelectorsAndPromptBudgets() throws IOException {
        List<Skill> skills = loadSkills();

        assertEquals(12, skills.size());
        assertTrue(skills.stream().noneMatch(skill -> "payment-oom-triage".equals(skill.id())));
        assertTrue(skills.stream()
                .map(Skill::id)
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(P0_SKILLS));
        assertUniqueSelectors(skills, Skill::alertNames, "alertName");
        assertUniqueSelectors(skills, Skill::metricNames, "metricName");
        assertUniqueSelectors(skills, Skill::runbookIds, "runbookId");
        TokenBudget tokenBudget = new TokenBudget();
        for (Skill skill : skills) {
            Path directory = Path.of(skill.skillPath()).getParent();
            assertEquals(directory.getFileName().toString(), skill.id(), skill.id());
            assertTrue(
                    tokenBudget.estimateTokens(skill.body()) <= 300,
                    () -> skill.id() + " exceeds the 300-token body budget");
            for (String runbookId : skill.runbookIds()) {
                assertTrue(
                        new ClassPathResource("runbooks/" + runbookId + ".md").exists(),
                        skill.id() + " references missing " + runbookId);
            }
        }
    }

    @Test
    void policiesShouldKeepRunbookAndCoreSkillLinks() throws IOException {
        List<Skill> skills = loadSkills();
        List<AlarmPolicy> catalogPolicies = policies("alarm-policies.yml");
        List<AlarmPolicy> activePolicies = policies("alarm-policies-node-mvp.yml");

        catalogPolicies.stream()
                .filter(policy -> policy.severity() == AlarmSeverity.P0 || policy.severity() == AlarmSeverity.P1)
                .forEach(policy -> assertTrue(
                        new ClassPathResource("runbooks/" + policy.runbookId() + ".md").exists(),
                        policy.id() + " references a missing runbook"));
        activePolicies.forEach(policy -> assertTrue(
                new ClassPathResource("runbooks/" + policy.runbookId() + ".md").exists(),
                policy.id() + " references a missing runbook"));
        SKILL_REQUIRED_ALERTS.forEach(alertName -> {
            List<String> owners = skills.stream()
                    .filter(skill -> skill.alertNames().contains(alertName))
                    .map(Skill::id)
                    .toList();
            assertEquals(1, owners.size(), alertName + " must have one primary Skill");
        });
    }

    @Test
    void everyDeclaredToolShouldExistBeReadOnlyAndSupportTheTask() throws IOException {
        List<Skill> skills = loadSkills();
        KubeOnCallProperties properties = new KubeOnCallProperties();
        AgentToolCatalog catalog = new AgentToolCatalog(
                List.of(
                        new KubernetesToolExecutor(null, properties),
                        new PrometheusToolExecutor(null, properties),
                        new AlertmanagerToolExecutor(null, properties)),
                new McpToolRegistry());
        Map<String, ToolDefinition> tools = catalog.allToolsByName();

        for (Skill skill : skills) {
            assertFalse(skill.toolWhitelist().isEmpty(), skill.id());
            for (String toolName : skill.toolWhitelist()) {
                ToolDefinition tool = tools.get(toolName);
                assertNotNull(tool, skill.id() + " declares unknown tool " + toolName);
                assertTrue(tool.readOnly(), skill.id() + " declares mutating tool " + toolName);
            }
            for (TaskType taskType : skill.applicableTasks()) {
                assertTrue(
                        skill.toolWhitelist().stream()
                                .map(tools::get)
                                .filter(java.util.Objects::nonNull)
                                .anyMatch(tool -> tool.supportedTaskTypes().contains(taskType)),
                        skill.id() + " has no whitelisted tool for " + taskType);
            }
            assertFalse(skill.toolWhitelist().contains("alertmanager.sendAlertEvent"), skill.id());
        }

        for (String skillId : P0_SKILLS) {
            Skill skill = skills.stream()
                    .filter(item -> skillId.equals(item.id()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(RiskLevel.LOW, skill.maxRisk());
            assertEquals(Set.of(TaskType.QUERY_LOGS, TaskType.QUERY_METRICS), Set.copyOf(skill.applicableTasks()));
        }
    }

    private static List<AlarmPolicy> policies(String resource) {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAlarm().setEnabled(true);
        properties.getAlarm().setPolicyLocation("classpath:" + resource);
        YamlAlarmPolicyRepository repository = new YamlAlarmPolicyRepository(properties);
        repository.load();
        return repository.findAll();
    }

    private static List<Skill> loadSkills() throws IOException {
        SkillFrontmatterParser parser = new SkillFrontmatterParser();
        try (var paths = Files.walk(SKILL_ROOT)) {
            return paths.filter(path -> "SKILL.md".equals(path.getFileName().toString()))
                    .sorted()
                    .map(path -> parse(parser, path))
                    .toList();
        }
    }

    private static Skill parse(SkillFrontmatterParser parser, Path path) {
        try {
            return parser.parse(
                    path.getParent().getFileName().toString(),
                    Files.readString(path),
                    SkillSource.BUILTIN,
                    path.toString());
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read " + path, ex);
        }
    }

    private static void assertUniqueSelectors(
            List<Skill> skills, java.util.function.Function<Skill, List<String>> selectors, String selectorType) {
        Map<String, String> owners = new HashMap<>();
        for (Skill skill : skills) {
            for (String selector : selectors.apply(skill)) {
                String normalized = selector.toLowerCase(Locale.ROOT);
                String previous = owners.putIfAbsent(normalized, skill.id());
                assertTrue(
                        previous == null || previous.equals(skill.id()),
                        selectorType + " " + selector + " is owned by " + previous + " and " + skill.id());
            }
        }
    }
}
