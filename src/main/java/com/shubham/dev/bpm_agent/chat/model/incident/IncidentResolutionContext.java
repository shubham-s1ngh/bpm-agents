package com.shubham.dev.bpm_agent.chat.model.incident;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record IncidentResolutionContext(String userPrompt,
                                        String workflowProcessDefinitionId,
                                        Set<String> involvedProcessDefinitionIds,
                                        Long processInstanceKey,
                                        List<Map<String, Object>> activeIncidents,
                                        Map<String, Object> diagnosticPayload) {
}
