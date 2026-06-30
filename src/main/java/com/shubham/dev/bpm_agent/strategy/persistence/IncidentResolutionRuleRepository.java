package com.shubham.dev.bpm_agent.strategy.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface IncidentResolutionRuleRepository extends JpaRepository<IncidentResolutionRuleEntity, Long> {

    List<IncidentResolutionRuleEntity> findByEnabledTrueAndWorkflowProcessDefinitionIdInOrderByPriorityAscIdAsc(
            Collection<String> workflowProcessDefinitionIds);

    List<IncidentResolutionRuleEntity> findByWorkflowProcessDefinitionIdAndEnabledTrueOrderByPriorityAscIdAsc(
            String workflowProcessDefinitionId);

    List<IncidentResolutionRuleEntity> findByWorkflowProcessDefinitionIdOrderByPriorityAscIdAsc(
            String workflowProcessDefinitionId);

    List<IncidentResolutionRuleEntity> findAllByOrderByWorkflowProcessDefinitionIdAscPriorityAscIdAsc();

    @Query("""
            select distinct rule.workflowProcessDefinitionId
            from IncidentResolutionRuleEntity rule
            order by rule.workflowProcessDefinitionId asc
            """)
    List<String> findDistinctWorkflowProcessDefinitionIds();
}
