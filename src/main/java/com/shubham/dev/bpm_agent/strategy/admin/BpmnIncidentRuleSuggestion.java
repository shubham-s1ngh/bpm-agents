package com.shubham.dev.bpm_agent.strategy.admin;

import java.util.List;

public record BpmnIncidentRuleSuggestion(
        String source,
        String sourceFileName,
        String workflowProcessDefinitionId,
        String processName,
        String elementId,
        String elementName,
        String elementType,
        String jobType,
        String calledElement,
        String instruction,
        List<String> errorTypes,
        List<Integer> httpStatusCodes,
        List<String> messageContains,
        String resolutionMode,
        String reason,
        String userFacingGuidance
) {
}
