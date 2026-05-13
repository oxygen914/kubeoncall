package com.kubeoncall.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagRouterTest {

    private final RagRouter ragRouter = new RagRouter();

    @Test
    void shouldRouteActionIntentToPlanner() {
        assertEquals("PLANNER", ragRouter.route("请帮我重启 payment-service"));
        assertEquals("PLANNER", ragRouter.route("scale order-service to 5"));
    }

    @Test
    void shouldRouteKnowledgeIntentToRag() {
        assertEquals("RAG", ragRouter.route("payment-service 告警怎么排查"));
        assertEquals("RAG", ragRouter.route("what is best practice for timeout"));
    }

    @Test
    void shouldRouteBlankQuestionToPlanner() {
        assertEquals("PLANNER", ragRouter.route("  \n  "));
    }
}
