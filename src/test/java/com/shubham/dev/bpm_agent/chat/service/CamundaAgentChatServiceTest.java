package com.shubham.dev.bpm_agent.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shubham.dev.bpm_agent.chat.CamundaDiagnosticTools;
import com.shubham.dev.bpm_agent.strategy.OrderWorkflowStrategy;
import com.shubham.dev.bpm_agent.strategy.WorkflowStrategyRegistry;
import com.shubham.dev.bpm_agent.strategy.persistence.IncidentResolutionRuleCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CamundaAgentChatServiceTest {

    @Test
    void extractsBareLongProcessInstanceKeyWhenRetryIntentIsExplicit() {
        CamundaAgentChatService service = new CamundaAgentChatService(
                mock(ChatClient.Builder.class),
                mock(CamundaDiagnosticTools.class),
                mock(WorkflowStrategyRegistry.class),
                mock(CamundaToolDispatchService.class),
                mock(CamundaDiagnosticReportService.class),
                new ObjectMapper());

        Optional<Long> extracted = service.extractRequestedProcessInstanceKey(
                "retry if there is incident for 2251799813819420", true);

        assertTrue(extracted.isPresent());
        assertEquals(2251799813819420L, extracted.get());
    }

    @Test
    void doesNotExtractBareLongProcessInstanceKeyWithoutRetryIntent() {
        CamundaAgentChatService service = new CamundaAgentChatService(
                mock(ChatClient.Builder.class),
                mock(CamundaDiagnosticTools.class),
                mock(WorkflowStrategyRegistry.class),
                mock(CamundaToolDispatchService.class),
                mock(CamundaDiagnosticReportService.class),
                new ObjectMapper());

        Optional<Long> extracted = service.extractRequestedProcessInstanceKey(
                "what happened for 2251799813819420", false);

        assertTrue(extracted.isEmpty());
    }

    @Test
    void resolvesProcessIncidentsWhenRetryIntentIsExplicitAfterSearch() {
        CamundaDiagnosticTools diagnosticTools = mock(CamundaDiagnosticTools.class);
        CamundaToolDispatchService toolDispatchService = mock(CamundaToolDispatchService.class);
        CamundaDiagnosticReportService reportService = mock(CamundaDiagnosticReportService.class);

        CamundaAgentChatService service = new CamundaAgentChatService(
                mock(ChatClient.Builder.class),
                diagnosticTools,
                mock(WorkflowStrategyRegistry.class),
                toolDispatchService,
                reportService,
                new ObjectMapper());

        when(diagnosticTools.resolveIncidentsByProcessInstance("2251799813950818"))
                .thenReturn(Map.of("status", "SUCCESS"));
        when(toolDispatchService.serialize(Map.of("status", "SUCCESS")))
                .thenReturn("{\"status\":\"SUCCESS\"}");
        when(reportService.generateReport(
                "conversation-id",
                "check for this order id ORD-55422 and retry the incident",
                "{\"status\":\"SUCCESS\"}",
                Optional.empty()))
                .thenReturn("Readable report");

        String response = service.handleSearchProcessInstancesResult(
                "[{\"processInstanceKey\":\"2251799813950818\",\"state\":\"ACTIVE\"}]",
                true,
                "conversation-id",
                "check for this order id ORD-55422 and retry the incident",
                Optional.empty());

        assertEquals("Readable report", response);
        verify(diagnosticTools).resolveIncidentsByProcessInstance("2251799813950818");
    }

    @Test
    void returnsNullWhenSearchPayloadHasNoProcessInstanceKey() {
        CamundaAgentChatService service = new CamundaAgentChatService(
                mock(ChatClient.Builder.class),
                mock(CamundaDiagnosticTools.class),
                mock(WorkflowStrategyRegistry.class),
                mock(CamundaToolDispatchService.class),
                mock(CamundaDiagnosticReportService.class),
                new ObjectMapper());

        String response = service.handleSearchProcessInstancesResult(
                "[]",
                true,
                "conversation-id",
                "retry incident",
                Optional.empty());

        assertNull(response);
    }

    @Test
    void extractsMultipleDistinctOrderIdsFromPrompt() {
        IncidentResolutionRuleCatalogService ruleCatalogService = mock(IncidentResolutionRuleCatalogService.class);
        when(ruleCatalogService.findRulesForWorkflows(org.mockito.ArgumentMatchers.anySet())).thenReturn(List.of());
        OrderWorkflowStrategy strategy = new OrderWorkflowStrategy(ruleCatalogService);

        List<String> orderIds = strategy.extractBusinessIdentifiers(
                "retry incidents for ORD-55421, ord-55422, and ORD-55421");

        assertEquals(List.of("ORD-55421", "ORD-55422"), orderIds);
    }

    @Test
    void handlesBulkOrderIncidentRetryDeterministically() throws Exception {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.defaultSystem(org.mockito.ArgumentMatchers.anyString())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.defaultAdvisors(org.mockito.ArgumentMatchers.<org.springframework.ai.chat.client.advisor.api.Advisor[]>any())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(mock(ChatClient.class));

        CamundaDiagnosticTools diagnosticTools = mock(CamundaDiagnosticTools.class);
        CamundaToolDispatchService toolDispatchService = mock(CamundaToolDispatchService.class);
        WorkflowStrategyRegistry strategyRegistry = mock(WorkflowStrategyRegistry.class);
        IncidentResolutionRuleCatalogService ruleCatalogService = mock(IncidentResolutionRuleCatalogService.class);
        when(ruleCatalogService.findRulesForWorkflows(org.mockito.ArgumentMatchers.anySet())).thenReturn(List.of());

        CamundaAgentChatService service = new CamundaAgentChatService(
                chatClientBuilder,
                diagnosticTools,
                strategyRegistry,
                toolDispatchService,
                mock(CamundaDiagnosticReportService.class),
                new ObjectMapper());

        when(strategyRegistry.getStrategyForPrompt("retry incidents for ORD-55421 and ORD-55422"))
                .thenReturn(Optional.of(new OrderWorkflowStrategy(ruleCatalogService)));

        when(diagnosticTools.searchProcessInstances("orderId", "ORD-55421"))
                .thenReturn(List.of(Map.of("processInstanceKey", "2251799813950818", "state", "ACTIVE")));
        when(diagnosticTools.searchProcessInstances("orderId", "ORD-55422"))
                .thenReturn(List.of());
        when(diagnosticTools.diagnoseProcessInstance(2251799813950818L))
                .thenReturn(Map.of(
                        "processInstanceKey", 2251799813950818L,
                        "processInstance", Map.of(
                                "processInstanceKey", "2251799813950818",
                                "processDefinitionId", "handleOrderId",
                                "state", "ACTIVE"
                        ),
                        "activeIncidents", List.of(Map.of(
                                "errorType", "JOB_NO_RETRIES",
                                "errorMessage", "inventory-reservation in subProcess_InventorySystem failed with HTTP 500 Internal Server Error. Response body: downstream timeout"
                        )),
                        "runtimeVariables", List.of(),
                        "activeSteps", List.of(),
                        "childProcessDiagnostics", List.of(),
                        "status", "Success"
                ));

        when(toolDispatchService.serialize(List.of(Map.of("processInstanceKey", "2251799813950818", "state", "ACTIVE"))))
                .thenReturn("[{\"processInstanceKey\":\"2251799813950818\",\"state\":\"ACTIVE\"}]");
        when(toolDispatchService.serialize(List.of()))
                .thenReturn("[]");
        when(toolDispatchService.serialize(Map.of(
                "processInstanceKey", 2251799813950818L,
                "processInstance", Map.of(
                        "processInstanceKey", "2251799813950818",
                        "processDefinitionId", "handleOrderId",
                        "state", "ACTIVE"
                ),
                "activeIncidents", List.of(Map.of(
                        "errorType", "JOB_NO_RETRIES",
                        "errorMessage", "inventory-reservation in subProcess_InventorySystem failed with HTTP 500 Internal Server Error. Response body: downstream timeout"
                )),
                "runtimeVariables", List.of(),
                "activeSteps", List.of(),
                "childProcessDiagnostics", List.of(),
                "status", "Success"
        ))).thenReturn("""
                {"processInstanceKey":2251799813950818,"processInstance":{"processInstanceKey":"2251799813950818","processDefinitionId":"handleOrderId","state":"ACTIVE"},"activeIncidents":[{"errorType":"JOB_NO_RETRIES","errorMessage":"inventory-reservation in subProcess_InventorySystem failed with HTTP 500 Internal Server Error. Response body: downstream timeout"}],"runtimeVariables":[],"activeSteps":[],"childProcessDiagnostics":[],"status":"Success"}
                """);
        when(diagnosticTools.resolveIncidentsByProcessInstance("2251799813950818"))
                .thenReturn(Map.of(
                        "status", "SUCCESS",
                        "message", "Resolved.",
                        "resolutionCommandAttempts", 1,
                        "verificationChecks", 2
                ));
        when(toolDispatchService.serialize(Map.of(
                "status", "SUCCESS",
                "message", "Resolved.",
                "resolutionCommandAttempts", 1,
                "verificationChecks", 2
        ))).thenReturn("""
                {"status":"SUCCESS","message":"Resolved.","resolutionCommandAttempts":1,"verificationChecks":2}
                """);

        String response = service.handlePrompt("retry incidents for ORD-55421 and ORD-55422");

        assertTrue(response.contains("# Bulk Incident Retry Report"));
        assertTrue(response.contains("Successful retries: 1"));
        assertTrue(response.contains("Identifiers not found: 1"));
        assertTrue(response.contains("orderId: ORD-55421"));
        assertTrue(response.contains("orderId: ORD-55422"));
        verify(diagnosticTools).resolveIncidentsByProcessInstance("2251799813950818");
    }

    @Test
    void bulkOrderRetryBlocksOrdersWhenStrategyRejectsIncident() throws Exception {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.defaultSystem(org.mockito.ArgumentMatchers.anyString())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.defaultAdvisors(org.mockito.ArgumentMatchers.<org.springframework.ai.chat.client.advisor.api.Advisor[]>any())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(mock(ChatClient.class));

        CamundaDiagnosticTools diagnosticTools = mock(CamundaDiagnosticTools.class);
        CamundaToolDispatchService toolDispatchService = mock(CamundaToolDispatchService.class);
        WorkflowStrategyRegistry strategyRegistry = mock(WorkflowStrategyRegistry.class);
        IncidentResolutionRuleCatalogService ruleCatalogService = mock(IncidentResolutionRuleCatalogService.class);
        when(ruleCatalogService.findRulesForWorkflows(org.mockito.ArgumentMatchers.anySet())).thenReturn(List.of());

        CamundaAgentChatService service = new CamundaAgentChatService(
                chatClientBuilder,
                diagnosticTools,
                strategyRegistry,
                toolDispatchService,
                mock(CamundaDiagnosticReportService.class),
                new ObjectMapper());

        when(strategyRegistry.getStrategyForPrompt("retry incidents for ORD-55431 and ORD-55432"))
                .thenReturn(Optional.of(new OrderWorkflowStrategy(ruleCatalogService)));

        when(diagnosticTools.searchProcessInstances("orderId", "ORD-55431"))
                .thenReturn(List.of(Map.of("processInstanceKey", "2251799813950831", "state", "ACTIVE")));
        when(diagnosticTools.searchProcessInstances("orderId", "ORD-55432"))
                .thenReturn(List.of(Map.of("processInstanceKey", "2251799813950832", "state", "ACTIVE")));

        when(toolDispatchService.serialize(List.of(Map.of("processInstanceKey", "2251799813950831", "state", "ACTIVE"))))
                .thenReturn("[{\"processInstanceKey\":\"2251799813950831\",\"state\":\"ACTIVE\"}]");
        when(toolDispatchService.serialize(List.of(Map.of("processInstanceKey", "2251799813950832", "state", "ACTIVE"))))
                .thenReturn("[{\"processInstanceKey\":\"2251799813950832\",\"state\":\"ACTIVE\"}]");

        Map<String, Object> blockedDiagnostic = Map.of(
                "processInstanceKey", 2251799813950831L,
                "processInstance", Map.of(
                        "processInstanceKey", "2251799813950831",
                        "processDefinitionId", "handleOrderId",
                        "state", "ACTIVE"
                ),
                "activeIncidents", List.of(Map.of(
                        "errorType", "JOB_NO_RETRIES",
                        "errorMessage", "payment-charge in subProcess_PaymentGateway failed with HTTP 400 Bad Request. Response body: invalid card token"
                )),
                "runtimeVariables", List.of(),
                "activeSteps", List.of(),
                "childProcessDiagnostics", List.of(),
                "status", "Success"
        );

        when(diagnosticTools.diagnoseProcessInstance(2251799813950831L)).thenReturn(blockedDiagnostic);
        when(diagnosticTools.diagnoseProcessInstance(2251799813950832L)).thenReturn(blockedDiagnostic);
        when(toolDispatchService.serialize(blockedDiagnostic)).thenReturn("""
                {"processInstanceKey":2251799813950831,"processInstance":{"processInstanceKey":"2251799813950831","processDefinitionId":"handleOrderId","state":"ACTIVE"},"activeIncidents":[{"errorType":"JOB_NO_RETRIES","errorMessage":"payment-charge in subProcess_PaymentGateway failed with HTTP 400 Bad Request. Response body: invalid card token"}],"runtimeVariables":[],"activeSteps":[],"childProcessDiagnostics":[],"status":"Success"}
                """);

        String response = service.handlePrompt("retry incidents for ORD-55431 and ORD-55432");

        assertTrue(response.contains("Blocked retries: 2"));
        assertTrue(response.contains("Status: BLOCKED"));
        assertTrue(response.contains("HTTP 400 Bad Request"));
        verify(diagnosticTools, never()).resolveIncidentsByProcessInstance("2251799813950831");
    }

    @Test
    void blocksSingleOrderRetryWhenStrategyRejectsIncident() {
        CamundaDiagnosticTools diagnosticTools = mock(CamundaDiagnosticTools.class);
        CamundaToolDispatchService toolDispatchService = mock(CamundaToolDispatchService.class);
        CamundaDiagnosticReportService reportService = mock(CamundaDiagnosticReportService.class);
        IncidentResolutionRuleCatalogService ruleCatalogService = mock(IncidentResolutionRuleCatalogService.class);
        when(ruleCatalogService.findRulesForWorkflows(org.mockito.ArgumentMatchers.anySet())).thenReturn(List.of());

        OrderWorkflowStrategy strategy = new OrderWorkflowStrategy(ruleCatalogService);
        CamundaAgentChatService service = new CamundaAgentChatService(
                mock(ChatClient.Builder.class),
                diagnosticTools,
                mock(WorkflowStrategyRegistry.class),
                toolDispatchService,
                reportService,
                new ObjectMapper());

        Map<String, Object> blockedDiagnostic = Map.of(
                "processInstanceKey", 2251799813950831L,
                "processInstance", Map.of(
                        "processInstanceKey", "2251799813950831",
                        "processDefinitionId", "handleOrderId",
                        "state", "ACTIVE"
                ),
                "activeIncidents", List.of(),
                "runtimeVariables", List.of(),
                "activeSteps", List.of(),
                "childProcessDiagnostics", List.of(Map.of(
                        "processInstanceKey", 2251799813950832L,
                        "processInstance", Map.of(
                                "processInstanceKey", "2251799813950832",
                                "processDefinitionId", "subProcess_PaymentGateway",
                                "state", "ACTIVE"
                        ),
                        "activeIncidents", List.of(Map.of(
                                "errorType", "JOB_NO_RETRIES",
                                "errorMessage", "payment-charge in subProcess_PaymentGateway failed with HTTP 400 Bad Request. Response body: invalid card token"
                        )),
                        "runtimeVariables", List.of(),
                        "activeSteps", List.of(),
                        "childProcessDiagnostics", List.of(),
                        "status", "Success"
                )),
                "status", "Success"
        );

        when(diagnosticTools.diagnoseProcessInstance(2251799813950831L)).thenReturn(blockedDiagnostic);
        when(toolDispatchService.serialize(blockedDiagnostic)).thenReturn("""
                {"processInstanceKey":2251799813950831,"processInstance":{"processInstanceKey":"2251799813950831","processDefinitionId":"handleOrderId","state":"ACTIVE"},"activeIncidents":[],"runtimeVariables":[],"activeSteps":[],"childProcessDiagnostics":[{"processInstanceKey":2251799813950832,"processInstance":{"processInstanceKey":"2251799813950832","processDefinitionId":"subProcess_PaymentGateway","state":"ACTIVE"},"activeIncidents":[{"errorType":"JOB_NO_RETRIES","errorMessage":"payment-charge in subProcess_PaymentGateway failed with HTTP 400 Bad Request. Response body: invalid card token"}],"runtimeVariables":[],"activeSteps":[],"childProcessDiagnostics":[],"status":"Success"}],"status":"Success"}
                """);
        when(reportService.generateReport(
                org.mockito.ArgumentMatchers.eq("conversation-id"),
                org.mockito.ArgumentMatchers.eq("retry incident for ORD-55431"),
                org.mockito.ArgumentMatchers.contains("\"postResolutionDiagnostics\""),
                org.mockito.ArgumentMatchers.eq(Optional.of(strategy))))
                .thenReturn("Blocked report with diagnostics");

        String response = service.handleSearchProcessInstancesResult(
                "[{\"processInstanceKey\":\"2251799813950831\",\"state\":\"ACTIVE\"}]",
                true,
                "conversation-id",
                "retry incident for ORD-55431",
                Optional.of(strategy));

        assertEquals("Blocked report with diagnostics", response);
        verify(diagnosticTools, never()).resolveIncidentsByProcessInstance("2251799813950831");
    }
}
