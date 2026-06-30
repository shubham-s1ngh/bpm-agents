package com.shubham.dev.bpm_agent.strategy.admin;

import java.util.List;

public record IncidentResolutionRuleAdminMetadata(
        List<String> workflowProcessDefinitionIds,
        List<String> resolutionModes
) {
}
