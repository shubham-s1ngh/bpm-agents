package com.shubham.dev.bpm_agent.strategy.persistence;

import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionMode;
import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionRule;
import com.shubham.dev.bpm_agent.strategy.WorkflowContextStrategy;
import com.shubham.dev.bpm_agent.strategy.WorkflowStrategyRegistry;
import com.shubham.dev.bpm_agent.strategy.admin.IncidentResolutionRuleAdminMetadata;
import com.shubham.dev.bpm_agent.strategy.admin.IncidentResolutionRuleAdminRecord;
import com.shubham.dev.bpm_agent.strategy.admin.IncidentResolutionRuleUpsertRequest;
import com.shubham.dev.bpm_agent.strategy.retrieval.WorkflowKnowledgeVectorStoreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        IncidentResolutionRuleCatalogService.class,
        IncidentResolutionRuleManagementService.class,
        IncidentResolutionRuleCatalogServiceTest.RegistryTestConfiguration.class
})
class IncidentResolutionRuleCatalogServiceTest {

    @Autowired
    private IncidentResolutionRuleRepository repository;

    @Autowired
    private IncidentResolutionRuleCatalogService catalogService;

    @Autowired
    private IncidentResolutionRuleManagementService managementService;

    @Test
    void flywayStartsWithEmptyConsultantManagedRuleCatalog() {
        List<IncidentResolutionRuleEntity> entities =
                repository.findByWorkflowProcessDefinitionIdAndEnabledTrueOrderByPriorityAscIdAsc("handleOrderId");

        assertTrue(entities.isEmpty());
    }

    @Test
    void returnsNoPersistedRuntimeRulesWhenCatalogIsEmpty() {
        List<IncidentResolutionRule> rules = catalogService.findRulesForWorkflow("handleOrderId");

        assertTrue(rules.isEmpty());
    }

    @Test
    void metadataIncludesRegisteredAndPersistedWorkflowIds() {
        repository.save(buildRuleEntity(
                "handleOrderId",
                10,
                true,
                "Persisted order rule.",
                "JOB_NO_RETRIES",
                "500",
                "inventory-reservation",
                IncidentResolutionMode.BY_PROCESS_INSTANCE.name(),
                "Persisted order reason.",
                ""
        ));

        IncidentResolutionRuleAdminMetadata metadata = managementService.fetchMetadata();

        assertTrue(metadata.workflowProcessDefinitionIds().contains("handleOrderId"));
        assertTrue(metadata.workflowProcessDefinitionIds().contains("loanProcessingFlow"));
        assertTrue(metadata.resolutionModes().contains("BLOCKED"));
        assertTrue(metadata.resolutionModes().contains("BY_PROCESS_INSTANCE"));
    }

    @Test
    void createsAndNormalizesRuleFromAdminRequest() {
        IncidentResolutionRuleAdminRecord record = managementService.createRule(new IncidentResolutionRuleUpsertRequest(
                "loanProcessingFlow",
                15,
                true,
                "Retry transient loan service failures.",
                List.of("job_no_retries", "JOB_NO_RETRIES"),
                List.of(500, 500),
                List.of("Loan-Service", "loan-service"),
                "by_process_instance",
                "Loan workflow allows retry for transient upstream failures.",
                "Retry after the transient dependency recovers."
        ));

        assertEquals("loanProcessingFlow", record.workflowProcessDefinitionId());
        assertEquals(List.of("JOB_NO_RETRIES"), record.errorTypes());
        assertEquals(List.of(500), record.httpStatusCodes());
        assertEquals(List.of("loan-service"), record.messageContains());
        assertEquals("BY_PROCESS_INSTANCE", record.resolutionMode());
    }

    @Test
    void updatesExistingRuleStateFromAdminRequest() {
        Long existingRuleId = repository.save(buildRuleEntity(
                "handleOrderId",
                10,
                true,
                "Initial consultant instruction.",
                "CALLED_ELEMENT_ERROR",
                null,
                "called element",
                IncidentResolutionMode.BLOCKED.name(),
                "Initial reason.",
                "Initial guidance."
        )).getId();

        IncidentResolutionRuleAdminRecord updated = managementService.updateRule(existingRuleId, new IncidentResolutionRuleUpsertRequest(
                "handleOrderId",
                11,
                false,
                "Updated consultant instruction.",
                List.of("CALLED_ELEMENT_ERROR"),
                List.of(),
                List.of("deployment mismatch"),
                "blocked",
                "Updated reason.",
                "Updated guidance."
        ));

        assertEquals(11, updated.priority());
        assertFalse(updated.enabled());
        assertEquals("Updated consultant instruction.", updated.instruction());
        assertEquals(List.of("deployment mismatch"), updated.messageContains());
        assertEquals("BLOCKED", updated.resolutionMode());
    }

    private IncidentResolutionRuleEntity buildRuleEntity(String workflowProcessDefinitionId,
                                                         Integer priority,
                                                         boolean enabled,
                                                         String instruction,
                                                         String errorTypes,
                                                         String httpStatusCodes,
                                                         String messageContains,
                                                         String resolutionMode,
                                                         String reason,
                                                         String userFacingGuidance) {
        IncidentResolutionRuleEntity entity = new IncidentResolutionRuleEntity();
        entity.setWorkflowProcessDefinitionId(workflowProcessDefinitionId);
        entity.setPriority(priority);
        entity.setEnabled(enabled);
        entity.setInstruction(instruction);
        entity.setErrorTypes(errorTypes);
        entity.setHttpStatusCodes(httpStatusCodes);
        entity.setMessageContains(messageContains);
        entity.setResolutionMode(resolutionMode);
        entity.setReason(reason);
        entity.setUserFacingGuidance(userFacingGuidance);
        return entity;
    }

    @TestConfiguration
    static class RegistryTestConfiguration {

        @Bean
        WorkflowStrategyRegistry workflowStrategyRegistry() {
            WorkflowContextStrategy loanStrategy = new WorkflowContextStrategy() {
                @Override
                public String getProcessDefinitionId() {
                    return "loanProcessingFlow";
                }

                @Override
                public boolean isApplicable(String userPrompt) {
                    return userPrompt.toLowerCase().contains("loan");
                }

                @Override
                public String generateBpmnContextInstructions() {
                    return "";
                }

                @Override
                public Map<String, String> translateVariables(String userPrompt) {
                    return Map.of();
                }

                @Override
                public String generateReportStructuringInstructions() {
                    return "";
                }
            };

            return new WorkflowStrategyRegistry(List.of(loanStrategy));
        }

        @Bean
        WorkflowKnowledgeVectorStoreService workflowKnowledgeVectorStoreService() {
            return mock(WorkflowKnowledgeVectorStoreService.class);
        }
    }
}
