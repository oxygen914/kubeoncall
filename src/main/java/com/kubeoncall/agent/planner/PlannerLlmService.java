package com.kubeoncall.agent.planner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.skill.SkillActivationService;
import com.kubeoncall.skill.SkillActivation;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PlannerLlmService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;
    private final SkillActivationService skillActivationService;

    public PlannerLlmService(ApplicationContext applicationContext,
                             ObjectMapper objectMapper,
                             KubeOnCallProperties properties,
                             SkillActivationService skillActivationService) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.skillActivationService = skillActivationService;
    }

    public Optional<PlannerLlmDecision> plan(String request, Map<String, Object> plannerKnowledge) {
        if (!properties.getAgent().isPlannerLlmEnabled()) {
            return Optional.empty();
        }
        Object chatClient = resolveChatClient();
        if (chatClient == null) {
            return Optional.empty();
        }

        try {
            String response = invokeChatClient(chatClient, buildSystemPrompt(), buildUserPrompt(request, plannerKnowledge));
            if (response == null || response.isBlank()) {
                return Optional.empty();
            }
            Optional<PlannerLlmDecision> firstDecision = parseDecision(response);
            if (firstDecision.isEmpty() || firstDecision.get().requestedSkills().isEmpty()
                    || skillActivationService == null) {
                return firstDecision;
            }
            SkillActivation activation = skillActivationService.activate(
                    request, plannerKnowledge, firstDecision.get().requestedSkills());
            if (!activation.active()) {
                return firstDecision;
            }
            Map<String, Object> expandedKnowledge = new LinkedHashMap<>(
                    plannerKnowledge == null ? Map.of() : plannerKnowledge);
            expandedKnowledge.put("activatedSkillIds", activation.skillIds());
            expandedKnowledge.put("activatedSkills", activation.skillSummaries());
            expandedKnowledge.put("activatedSkillToolWhitelist", activation.toolWhitelist());
            expandedKnowledge.put("activatedSkillMaxRisk", activation.maxRisk() == null ? null : activation.maxRisk().name());
            expandedKnowledge.put("skillPrompt", activation.prompt());
            String refinedResponse = invokeChatClient(
                    chatClient, buildSystemPrompt(), buildUserPrompt(request, expandedKnowledge));
            Optional<PlannerLlmDecision> refined = refinedResponse == null || refinedResponse.isBlank()
                    ? Optional.empty()
                    : parseDecision(refinedResponse);
            return refined.map(decision -> decision.requestedSkills().isEmpty()
                            ? decision.withRequestedSkills(activation.skillIds())
                            : decision)
                    .or(() -> firstDecision);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private Object resolveChatClient() {
        Object direct = resolveBeanByClassName("org.springframework.ai.chat.client.ChatClient")
                .or(() -> resolveBeanByClassName("org.springframework.ai.chat.ChatClient"))
                .orElse(null);
        if (direct != null) {
            return direct;
        }
        Object builder = resolveBeanByClassName("org.springframework.ai.chat.client.ChatClient$Builder").orElse(null);
        if (builder != null) {
            Object built = invokeNoArg(builder, "build");
            if (built != null) {
                return built;
            }
        }
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean = applicationContext.getBean(beanName);
            String className = bean.getClass().getName();
            if (className.contains("ChatClient") && !className.contains("Builder")) {
                return bean;
            }
        }
        return null;
    }

    private Optional<Object> resolveBeanByClassName(String className) {
        try {
            Class<?> type = Class.forName(className);
            return Optional.ofNullable(applicationContext.getBeanProvider(type).getIfAvailable());
        } catch (ClassNotFoundException ignored) {
            return Optional.empty();
        }
    }

    private String invokeChatClient(Object chatClient, String systemPrompt, String userPrompt) {
        Object promptSpec = invokeNoArg(chatClient, "prompt");
        if (promptSpec != null) {
            invokeOneArgIfPresent(promptSpec, "system", systemPrompt);
            invokeOneArgIfPresent(promptSpec, "user", userPrompt);
            Object callResult = invokeNoArg(promptSpec, "call");
            Object content = invokeNoArg(callResult, "content");
            return content == null ? null : String.valueOf(content);
        }

        Object promptSpecWithUser = invokeOneArgIfPresent(chatClient, "prompt", userPrompt);
        if (promptSpecWithUser != null) {
            invokeOneArgIfPresent(promptSpecWithUser, "system", systemPrompt);
            Object callResult = invokeNoArg(promptSpecWithUser, "call");
            Object content = invokeNoArg(callResult, "content");
            return content == null ? null : String.valueOf(content);
        }

        Object response = invokeOneArgIfPresent(chatClient, "call", systemPrompt + "\n\n" + userPrompt);
        return response == null ? null : String.valueOf(response);
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object invokeOneArgIfPresent(Object target, String methodName, String argument) {
        if (target == null) {
            return null;
        }
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(methodName)
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(String.class)) {
                try {
                    return method.invoke(target, argument);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private Optional<PlannerLlmDecision> parseDecision(String response) throws Exception {
        String json = extractJsonObject(response);
        JsonNode root = objectMapper.readTree(json);
        String intent = text(root, "intent");
        TaskType taskType = enumValue(TaskType.class, text(root, "taskType")).orElse(null);
        RiskLevel riskLevel = enumValue(RiskLevel.class, text(root, "riskLevel")).orElse(null);
        Map<String, Object> parameters = root.has("parameters") && root.get("parameters").isObject()
                ? objectMapper.convertValue(root.get("parameters"), MAP_TYPE)
                : Map.of();
        List<String> missingSignals = root.has("missingSignals") && root.get("missingSignals").isArray()
                ? objectMapper.convertValue(root.get("missingSignals"), STRING_LIST_TYPE)
                : List.of();
        List<String> requestedSkills = root.has("requestedSkills") && root.get("requestedSkills").isArray()
                ? objectMapper.convertValue(root.get("requestedSkills"), STRING_LIST_TYPE)
                : List.of();

        if ((intent == null || intent.isBlank()) && taskType == null) {
            return Optional.empty();
        }
        return Optional.of(new PlannerLlmDecision(
                intent,
                text(root, "confidence"),
                text(root, "target"),
                text(root, "targetSource"),
                taskType,
                riskLevel,
                new LinkedHashMap<>(parameters),
                missingSignals,
                requestedSkills,
                text(root, "summary")
        ));
    }

    private String extractJsonObject(String response) {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    private <E extends Enum<E>> Optional<E> enumValue(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, value.trim().toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private String text(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        return node == null || node.isNull() ? null : node.asText();
    }

    private String buildSystemPrompt() {
        return """
                You are KubeOnCall's planner agent. Produce one compact JSON object only.
                Required fields: intent, confidence, target, targetSource, taskType, riskLevel, parameters, missingSignals, requestedSkills, summary.
                taskType must be one of QUERY_LOGS, QUERY_METRICS, PATCH_CONFIG, RESTART_SERVICE, SCALE_WORKLOAD, CLEAN_DATA, EXECUTE_SCRIPT.
                riskLevel must be LOW, MEDIUM, HIGH, or CRITICAL.
                Prefer safe read-only diagnostics unless the user clearly asks for a change.
                Skills are operational hints. Use activated skill bodies only after validating current state.
                Set requestedSkills to registered skill ids when a listed skill is relevant; otherwise use an empty array.
                """ + "\n\n" + skillIndex();
    }

    public SkillActivation activateRequestedSkills(String request,
                                                   Map<String, Object> context,
                                                   List<String> requestedSkillIds) {
        if (skillActivationService == null || requestedSkillIds == null || requestedSkillIds.isEmpty()) {
            return SkillActivation.empty();
        }
        return skillActivationService.activate(request, context, requestedSkillIds);
    }

    private String buildUserPrompt(String request, Map<String, Object> plannerKnowledge) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request", request);
        payload.put("plannerKnowledge", plannerKnowledge == null ? Map.of() : plannerKnowledge);
        return objectMapper.writeValueAsString(payload);
    }

    private String skillIndex() {
        if (skillActivationService == null) {
            return "Skill index unavailable.";
        }
        try {
            return skillActivationService.indexForPrompt();
        } catch (RuntimeException ex) {
            return "Skill index unavailable: " + ex.getMessage();
        }
    }
}
