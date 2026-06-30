package com.shubham.dev.bpm_agent.strategy.persistence;

import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionMode;
import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionRule;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class IncidentResolutionRuleCatalogService {

    private final IncidentResolutionRuleRepository repository;

    public IncidentResolutionRuleCatalogService(IncidentResolutionRuleRepository repository) {
        this.repository = repository;
    }

    public List<IncidentResolutionRule> findRulesForWorkflows(Set<String> workflowProcessDefinitionIds) {
        if (workflowProcessDefinitionIds == null || workflowProcessDefinitionIds.isEmpty()) {
            return List.of();
        }

        return repository.findByEnabledTrueAndWorkflowProcessDefinitionIdInOrderByPriorityAscIdAsc(workflowProcessDefinitionIds)
                .stream()
                .map(this::toRule)
                .toList();
    }

    private IncidentResolutionRule toRule(IncidentResolutionRuleEntity entity) {
        return new IncidentResolutionRule(
                entity.getInstruction(),
                Set.of(entity.getWorkflowProcessDefinitionId()),
                splitToStringSet(entity.getErrorTypes()),
                splitToIntegerSet(entity.getHttpStatusCodes()),
                splitToList(entity.getMessageContains()),
                IncidentResolutionMode.valueOf(entity.getResolutionMode()),
                entity.getReason(),
                entity.getUserFacingGuidance()
        );
    }

    private Set<String> splitToStringSet(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .forEach(result::add);
        return Set.copyOf(result);
    }

    private Set<Integer> splitToIntegerSet(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        Set<Integer> result = new LinkedHashSet<>();
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(Integer::parseInt)
                .forEach(result::add);
        return Set.copyOf(result);
    }

    private List<String> splitToList(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
