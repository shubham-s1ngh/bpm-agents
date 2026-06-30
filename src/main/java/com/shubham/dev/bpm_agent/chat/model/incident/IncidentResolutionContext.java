package com.shubham.dev.bpm_agent.chat.model.incident;

import java.util.Set;
import java.util.List;
import java.util.Map;

public record IncidentResolutionContext(
        String userPrompt,
        String processDefinitionId,
        Set<String> involvedProcessDefinitionIds,
        Long processInstanceKey,
        List<Map<String, Object>> activeIncidents,
        Map<String, Object> diagnosticSnapshot
) {
}
