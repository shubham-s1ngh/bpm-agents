package com.shubham.dev.bpm_agent.strategy;

import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionContext;
import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionDecision;
import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionMode;
import com.shubham.dev.bpm_agent.strategy.persistence.IncidentResolutionRuleCatalogService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderWorkflowStrategyTest {

    private final IncidentResolutionRuleCatalogService ruleCatalogService = mock(IncidentResolutionRuleCatalogService.class);
    private final OrderWorkflowStrategy strategy = new OrderWorkflowStrategy(ruleCatalogService);

    OrderWorkflowStrategyTest() {
        when(ruleCatalogService.findRulesForWorkflows(org.mockito.ArgumentMatchers.anySet())).thenReturn(List.of());
    }

    @Test
    void prefersProcessInstanceResolutionForOrderWorkflow() {
        assertEquals(IncidentResolutionMode.BY_PROCESS_INSTANCE, strategy.preferredResolutionMode());
    }

    @Test
    void blocksDeploymentMismatchCalledElementIncidents() {
        IncidentResolutionContext context = new IncidentResolutionContext(
                "retry this order incident",
                "handleOrderId",
                java.util.Set.of("handleOrderId"),
                2251799813819420L,
                List.of(Map.of(
                        "errorType", "CALLED_ELEMENT_ERROR",
                        "errorMessage", "Expected to evaluate called element, but no process found for called element 'regularCategory_ProcessId1'."
                )),
                Map.of()
        );

        IncidentResolutionDecision decision = strategy.buildResolutionDecision(context);

        assertFalse(decision.allowed());
        assertEquals(IncidentResolutionMode.BLOCKED, decision.mode());
        assertTrue(decision.userFacingGuidance().contains("deployment or configuration mismatch"));
    }

    @Test
    void skipsResolutionWhenNoActiveIncidentEvidenceExists() {
        IncidentResolutionContext context = new IncidentResolutionContext(
                "retry this order incident",
                "handleOrderId",
                java.util.Set.of("handleOrderId"),
                2251799813819420L,
                List.of(),
                Map.of()
        );

        IncidentResolutionDecision decision = strategy.buildResolutionDecision(context);

        assertFalse(decision.allowed());
        assertEquals(IncidentResolutionMode.NO_ACTION, decision.mode());
    }

    @Test
    void allowsResolutionForNonDeploymentOrderIncidents() {
        IncidentResolutionContext context = new IncidentResolutionContext(
                "retry this order incident",
                "handleOrderId",
                java.util.Set.of("handleOrderId", "subProcess_InventorySystem"),
                2251799813819420L,
                List.of(Map.of(
                        "errorType", "JOB_NO_RETRIES",
                        "errorMessage", "inventory-reservation in subProcess_InventorySystem failed with HTTP 500 Internal Server Error. Response body: downstream timeout"
                )),
                Map.of()
        );

        IncidentResolutionDecision decision = strategy.buildResolutionDecision(context);

        assertTrue(decision.allowed());
        assertEquals(IncidentResolutionMode.BY_PROCESS_INSTANCE, decision.mode());
    }

    @Test
    void blocksPaymentBadRequestIncidents() {
        IncidentResolutionContext context = new IncidentResolutionContext(
                "retry this order incident",
                "handleOrderId",
                java.util.Set.of("handleOrderId", "subProcess_PaymentGateway"),
                2251799813819420L,
                List.of(Map.of(
                        "errorType", "JOB_NO_RETRIES",
                        "errorMessage", "payment-charge in subProcess_PaymentGateway failed with HTTP 400 Bad Request. Response body: invalid card token"
                )),
                Map.of()
        );

        IncidentResolutionDecision decision = strategy.buildResolutionDecision(context);

        assertFalse(decision.allowed());
        assertEquals(IncidentResolutionMode.BLOCKED, decision.mode());
        assertTrue(decision.userFacingGuidance().contains("HTTP 400 Bad Request"));
    }

    @Test
    void defaultStrategyDecisionAllowsResolutionWhenSupported() {
        WorkflowContextStrategy genericStrategy = new WorkflowContextStrategy() {
            @Override
            public String getProcessDefinitionId() {
                return "genericProcess";
            }

            @Override
            public boolean isApplicable(String userPrompt) {
                return false;
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

        IncidentResolutionDecision decision = genericStrategy.buildResolutionDecision(
                new IncidentResolutionContext(
                        "retry",
                        "genericProcess",
                        java.util.Set.of("genericProcess"),
                        1L,
                        List.of(Map.of("errorType", "JOB_NO_RETRIES", "errorMessage", "generic failure")),
                        Map.of())
        );

        assertTrue(decision.allowed());
        assertEquals(IncidentResolutionMode.BY_PROCESS_INSTANCE, decision.mode());
    }

    @Test
    void allowsPersistedSubprocessRuleToMatchChildIncident() {
        when(ruleCatalogService.findRulesForWorkflows(org.mockito.ArgumentMatchers.anySet())).thenReturn(List.of(
                new com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionRule(
                        "Block child payment bad request incidents.",
                        java.util.Set.of("subProcess_PaymentGateway"),
                        java.util.Set.of("job_no_retries"),
                        java.util.Set.of(400),
                        List.of("payment-charge", "bad request"),
                        IncidentResolutionMode.BLOCKED,
                        "Child payment subprocess incidents should stay blocked.",
                        "Fix the payment request before retry."
                )
        ));

        IncidentResolutionDecision decision = strategy.buildResolutionDecision(new IncidentResolutionContext(
                "retry this order incident",
                "handleOrderId",
                java.util.Set.of("handleOrderId", "subProcess_PaymentGateway"),
                2251799813819420L,
                List.of(Map.of(
                        "errorType", "JOB_NO_RETRIES",
                        "errorMessage", "payment-charge in subProcess_PaymentGateway failed with HTTP 400 Bad Request."
                )),
                Map.of()
        ));

        assertFalse(decision.allowed());
        assertEquals(IncidentResolutionMode.BLOCKED, decision.mode());
    }
}
