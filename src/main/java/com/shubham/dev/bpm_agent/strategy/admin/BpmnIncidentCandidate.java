package com.shubham.dev.bpm_agent.strategy.admin;

public record BpmnIncidentCandidate(
        String fileName,
        String processDefinitionId,
        String processName,
        String elementId,
        String elementName,
        String elementType,
        String jobType,
        String calledElement
) {
}
