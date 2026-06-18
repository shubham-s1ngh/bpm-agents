package com.shubham.dev.bpm_agent.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shubham.dev.bpm_agent.chat.CamundaDiagnosticTools;
import com.shubham.dev.bpm_agent.strategy.WorkflowStrategyRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
}
