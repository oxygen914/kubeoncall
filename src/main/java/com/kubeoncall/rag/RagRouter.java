package com.kubeoncall.rag;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RagRouter {

    private static final List<String> ACTION_KEYWORDS = List.of(
            "重启", "执行", "扩容", "缩容", "删除", "rollback", "restart", "scale", "patch", "apply"
    );
    private static final List<String> KNOWLEDGE_KEYWORDS = List.of(
            "为什么", "怎么", "sop", "手册", "排查", "原因", "如何", "最佳实践", "what", "why", "how"
    );

    public String route(String question) {
        if (question == null || question.isBlank()) {
            return "PLANNER";
        }
        String normalized = question.toLowerCase().trim();
        if (containsAny(normalized, ACTION_KEYWORDS)) {
            return "PLANNER";
        }
        if (containsAny(normalized, KNOWLEDGE_KEYWORDS)) {
            return "RAG";
        }
        return "RAG";
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
