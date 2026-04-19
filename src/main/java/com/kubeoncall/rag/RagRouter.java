package com.kubeoncall.rag;

import org.springframework.stereotype.Component;

@Component
public class RagRouter {

    public String route(String question) {
        if (question == null || question.isBlank()) {
            return "PLANNER";
        }
        String normalized = question.toLowerCase();
        if (normalized.contains("为什么")
                || normalized.contains("怎么")
                || normalized.contains("sop")
                || normalized.contains("手册")
                || normalized.contains("排查")
                || normalized.contains("原因")) {
            return "RAG";
        }
        if (normalized.contains("重启")
                || normalized.contains("执行")
                || normalized.contains("扩容")
                || normalized.contains("删除")
                || normalized.contains("rollback")) {
            return "PLANNER";
        }
        return "RAG";
    }
}
