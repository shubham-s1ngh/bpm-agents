package com.shubham.dev.bpm_agent.strategy.admin;

import java.util.List;

public record BpmnProcessSummary(
        String fileName,
        String processDefinitionId,
        String processName,
        List<BpmnIncidentCandidate> candidates
) {
}
