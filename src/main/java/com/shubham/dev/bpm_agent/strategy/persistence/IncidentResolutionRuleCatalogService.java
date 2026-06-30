package com.shubham.dev.bpm_agent.strategy.persistence;

import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionMode;
import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionRule;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class IncidentResolutionRuleCatalogService {

    private final IncidentResolutionRuleRepository repository;

    public IncidentResolutionRuleCatalogService(IncidentResolutionRuleRepository repository) {
        this.repository = repository;
    }

    public List<IncidentResolutionRule> findRulesForWorkflow(String workflowProcessDefinitionId) {
        return repository.findByWorkflowProcessDefinitionIdAndEnabledTrueOrderByPriorityAscIdAsc(workflowProcessDefinitionId)
                .stream()
                .map(this::toRule)
                .toList();
    }

    public List<IncidentResolutionRule> findRulesForWorkflows(Collection<String> workflowProcessDefinitionIds) {
        if (workflowProcessDefinitionIds == null || workflowProcessDefinitionIds.isEmpty()) {
            return List.of();
        }
        return repository.findByWorkflowProcessDefinitionIdInAndEnabledTrueOrderByPriorityAscIdAsc(workflowProcessDefinitionIds)
                .stream()
                .map(this::toRule)
                .toList();
    }

    private IncidentResolutionRule toRule(IncidentResolutionRuleEntity entity) {
        return new IncidentResolutionRule(
                entity.getInstruction(),
                Set.of(entity.getWorkflowProcessDefinitionId()),
                parseCsvSet(entity.getErrorTypes()),
                parseIntegerSet(entity.getHttpStatusCodes()),
                parseCsvList(entity.getMessageContains()),
                IncidentResolutionMode.valueOf(entity.getResolutionMode()),
                entity.getReason(),
                entity.getUserFacingGuidance() == null ? "" : entity.getUserFacingGuidance()
        );
    }

    private Set<String> parseCsvSet(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    private Set<Integer> parseIntegerSet(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(Integer::parseInt)
                .collect(Collectors.toSet());
    }

    private List<String> parseCsvList(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
