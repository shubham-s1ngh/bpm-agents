package com.shubham.dev.bpm_agent.strategy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class WorkflowStrategyRegistry {

    private final List<WorkflowContextStrategy> strategies;

    public WorkflowStrategyRegistry(List<WorkflowContextStrategy> strategies) {
        this.strategies = strategies;
    }

    /**
     * Resolves the matching workflow metadata context based on text patterns found in the user prompt.
     */
    public Optional<WorkflowContextStrategy> getStrategyForPrompt(String userPrompt) {
        return strategies.stream()
                .filter(strategy -> strategy.isApplicable(userPrompt))
                .findFirst();
    }

    public List<String> getRegisteredProcessDefinitionIds() {
        return strategies.stream()
                .map(WorkflowContextStrategy::getProcessDefinitionId)
                .distinct()
                .sorted()
                .toList();
    }
}
