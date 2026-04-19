package com.kubeoncall.agent.verifier;

import com.kubeoncall.agent.base.PipelineAgent;
import com.kubeoncall.agent.node.AgentNode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class VerifierAgent extends PipelineAgent {

    private final VerifierThinkNode verifierThinkNode;
    private final VerifierApprovalNode verifierApprovalNode;

    public VerifierAgent(VerifierThinkNode verifierThinkNode, VerifierApprovalNode verifierApprovalNode) {
        this.verifierThinkNode = verifierThinkNode;
        this.verifierApprovalNode = verifierApprovalNode;
    }

    @Override
    public String getAgentName() {
        return "verifierAgent";
    }

    @Override
    protected List<AgentNode> nodes() {
        return List.of(verifierThinkNode, verifierApprovalNode);
    }
}
