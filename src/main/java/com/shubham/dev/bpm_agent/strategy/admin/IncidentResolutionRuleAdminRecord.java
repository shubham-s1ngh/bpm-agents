package com.shubham.dev.bpm_agent.strategy.admin;

import java.util.List;

public record IncidentResolutionRuleAdminRecord(
        Long id,
        String workflowProcessDefinitionId,
        Integer priority,
        boolean enabled,
        String instruction,
        List<String> errorTypes,
        List<Integer> httpStatusCodes,
        List<String> messageContains,
        String resolutionMode,
        String reason,
        String userFacingGuidance
) {
}
