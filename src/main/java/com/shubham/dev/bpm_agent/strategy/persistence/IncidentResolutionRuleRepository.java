package com.shubham.dev.bpm_agent.strategy.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface IncidentResolutionRuleRepository extends JpaRepository<IncidentResolutionRuleEntity, Long> {

    List<IncidentResolutionRuleEntity> findByEnabledTrueAndWorkflowProcessDefinitionIdInOrderByPriorityAscIdAsc(
            Collection<String> workflowProcessDefinitionIds);
}
