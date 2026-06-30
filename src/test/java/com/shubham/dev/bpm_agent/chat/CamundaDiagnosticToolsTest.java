package com.shubham.dev.bpm_agent.chat;

import com.shubham.dev.bpm_agent.camunda.CamundaOrchestrationClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CamundaDiagnosticToolsTest {

    @Test
    void resolveProcessIncidentsFailsWhenIncidentsRemainAfterVerification() throws Exception {
        CamundaOrchestrationClient client = mock(CamundaOrchestrationClient.class);
        CamundaDiagnosticTools tools = new CamundaDiagnosticTools(client, 8, 500L);

        List<Map<String, Object>> activeIncidents = List.of(Map.of(
                "incidentKey", "2251799813819486",
                "state", "ACTIVE"
        ));

        when(client.getIncidents(2251799813819420L))
                .thenReturn(activeIncidents)
                .thenReturn(activeIncidents);
        when(client.searchChildProcessInstances(2251799813819420L, 100)).thenReturn(List.of());
        doNothing().when(client).resolveIncidentsByProcessInstance(2251799813819420L);

        Map<String, Object> result = tools.resolveIncidentsByProcessInstance("2251799813819420");

        assertEquals("FAILED", result.get("status"));
        assertTrue(result.containsKey("remainingIncidents"));
        assertEquals(1, result.get("resolutionCommandAttempts"));
        assertEquals(8, result.get("verificationChecks"));
    }

    @Test
    void resolveProcessIncidentsSucceedsWhenIncidentsClearAfterVerification() throws Exception {
        CamundaOrchestrationClient client = mock(CamundaOrchestrationClient.class);
        CamundaDiagnosticTools tools = new CamundaDiagnosticTools(client, 8, 500L);

        List<Map<String, Object>> activeIncidents = List.of(Map.of(
                "incidentKey", "2251799813819486",
                "state", "ACTIVE"
        ));

        when(client.getIncidents(2251799813819420L))
                .thenReturn(activeIncidents)
                .thenReturn(List.of());
        when(client.searchChildProcessInstances(2251799813819420L, 100)).thenReturn(List.of());
        doNothing().when(client).resolveIncidentsByProcessInstance(2251799813819420L);

        Map<String, Object> result = tools.resolveIncidentsByProcessInstance("2251799813819420");

        assertEquals("SUCCESS", result.get("status"));
        assertTrue(result.containsKey("resolvedIncidents"));
        assertEquals(1, result.get("resolutionCommandAttempts"));
    }

    @Test
    void resolveProcessIncidentsSucceedsWhenIncidentClearsAfterShortDelay() throws Exception {
        CamundaOrchestrationClient client = mock(CamundaOrchestrationClient.class);
        CamundaDiagnosticTools tools = new CamundaDiagnosticTools(client, 8, 500L);

        List<Map<String, Object>> activeIncidents = List.of(Map.of(
                "incidentKey", "2251799813819486",
                "state", "ACTIVE"
        ));

        when(client.getIncidents(2251799813819420L))
                .thenReturn(activeIncidents)
                .thenReturn(activeIncidents)
                .thenReturn(List.of());
        when(client.searchChildProcessInstances(2251799813819420L, 100)).thenReturn(List.of());
        doNothing().when(client).resolveIncidentsByProcessInstance(2251799813819420L);

        Map<String, Object> result = tools.resolveIncidentsByProcessInstance("2251799813819420");

        assertEquals("SUCCESS", result.get("status"));
        assertTrue(result.containsKey("resolvedIncidents"));
        assertEquals(1, result.get("resolutionCommandAttempts"));
        assertEquals(2, result.get("verificationChecks"));
    }

    @Test
    void resolveProcessIncidentsIgnoresNonActiveIncidentHistory() throws Exception {
        CamundaOrchestrationClient client = mock(CamundaOrchestrationClient.class);
        CamundaDiagnosticTools tools = new CamundaDiagnosticTools(client, 8, 500L);

        List<Map<String, Object>> nonActiveIncidents = List.of(
                Map.of("incidentKey", "2251799813819486", "state", "RESOLVED"),
                Map.of("incidentKey", "2251799813819487", "state", "CLOSED")
        );

        when(client.getIncidents(2251799813819420L)).thenReturn(nonActiveIncidents);
        when(client.searchChildProcessInstances(2251799813819420L, 100)).thenReturn(List.of());

        Map<String, Object> result = tools.resolveIncidentsByProcessInstance("2251799813819420");

        assertEquals("NO_ACTION", result.get("status"));
        assertTrue(result.get("message").toString().contains("No active incidents"));
        assertTrue(result.containsKey("postResolutionDiagnostics"));
        assertEquals(0, result.get("resolutionCommandAttempts"));
        assertEquals(1, result.get("verificationChecks"));
    }

    @Test
    void resolveProcessIncidentsTraversesChildProcessIncidents() throws Exception {
        CamundaOrchestrationClient client = mock(CamundaOrchestrationClient.class);
        CamundaDiagnosticTools tools = new CamundaDiagnosticTools(client, 8, 500L);

        List<Map<String, Object>> childActiveIncidents = List.of(Map.of(
                "incidentKey", "2251799813819486",
                "state", "ACTIVE"
        ));

        when(client.searchChildProcessInstances(2251799813819420L, 100))
                .thenReturn(List.of(Map.of("processInstanceKey", "2251799813819500")));
        when(client.searchChildProcessInstances(2251799813819500L, 100)).thenReturn(List.of());
        when(client.getIncidents(2251799813819420L))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(client.getIncidents(2251799813819500L))
                .thenReturn(childActiveIncidents)
                .thenReturn(List.of());
        doNothing().when(client).resolveIncidentsByProcessInstance(2251799813819500L);

        Map<String, Object> result = tools.resolveIncidentsByProcessInstance("2251799813819420");

        assertEquals("SUCCESS", result.get("status"));
        assertEquals(1, result.get("resolutionCommandAttempts"));
        assertTrue(result.containsKey("affectedProcessInstanceKeys"));
        verify(client).resolveIncidentsByProcessInstance(2251799813819500L);
    }
}
