package com.kubeoncall.rag;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public class QueryRewriteService {

    private static final Pattern IP_PATTERN = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern ALERT_CODE_PATTERN = Pattern.compile("[A-Z]{2,}-\\d+");
    private static final Pattern POD_PATTERN =
            Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?(?:-[a-z0-9]([-a-z0-9]*[a-z0-9])?)+");

    public String rewrite(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        String normalized = question.trim().replaceAll("\\s+", " ");
        Set<String> preservedTokens = new LinkedHashSet<>();
        collectMatches(IP_PATTERN, normalized, preservedTokens);
        collectMatches(ALERT_CODE_PATTERN, normalized, preservedTokens);
        collectMatches(POD_PATTERN, normalized, preservedTokens);

        String rewritten = normalized
                .replace("咋办", "怎么处理")
                .replace("怎么办", "怎么处理")
                .replace("告警", "告警 故障")
                .replace("pod", "pod 容器")
                .replace("节点", "节点 服务器");

        if (!preservedTokens.isEmpty()) {
            rewritten = rewritten + " 关键标识: " + String.join(" ", preservedTokens);
        }
        return rewritten.trim();
    }

    private void collectMatches(Pattern pattern, String text, Set<String> collector) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            collector.add(matcher.group());
        }
    }
}
