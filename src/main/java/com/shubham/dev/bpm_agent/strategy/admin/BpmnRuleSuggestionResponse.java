package com.shubham.dev.bpm_agent.strategy.admin;

import java.util.List;

public record BpmnRuleSuggestionResponse(
        List<BpmnProcessSummary> processes,
        List<BpmnIncidentRuleSuggestion> suggestions
) {
}
