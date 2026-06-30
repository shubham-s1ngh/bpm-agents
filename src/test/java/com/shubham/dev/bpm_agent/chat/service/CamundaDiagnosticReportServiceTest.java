package com.shubham.dev.bpm_agent.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shubham.dev.bpm_agent.chat.validation.CamundaReportGroundingValidator;
import com.shubham.dev.bpm_agent.strategy.retrieval.WorkflowKnowledgeVectorStoreService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CamundaDiagnosticReportServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CamundaEvidenceDigestService evidenceDigestService =
            new CamundaEvidenceDigestService(objectMapper);
    private final CamundaReportGroundingValidator groundingValidator =
            new CamundaReportGroundingValidator(evidenceDigestService);
    private final CamundaDiagnosticReportService reportService =
            new CamundaDiagnosticReportService(
                    objectMapper,
                    mock(ChatClient.Builder.class),
                    evidenceDigestService,
                    groundingValidator,
                    mock(WorkflowKnowledgeVectorStoreService.class));

    @Test
    void buildsStableDiagnosticContractWithoutLegacySections() {
        String contract = reportService.buildStableReportContract(false);

        assertTrue(contract.contains("# Diagnostic Report"));
        assertTrue(contract.contains("## Process Instance Overview"));
        assertTrue(contract.contains("## Variables"));
        assertTrue(contract.contains("## Flow Elements"));
        assertTrue(contract.contains("Do not add sections such as `Camunda State Semantics`, `Process Overview`, `Routing Interpretation`, or `Remediation Action`."));
    }

    @Test
    void buildsStableResolutionContractForMutationPayloads() {
        String contract = reportService.buildStableReportContract(true);

        assertTrue(contract.contains("## Resolution Outcome"));
        assertTrue(contract.contains("## Active Incidents Before Resolution"));
        assertTrue(contract.contains("## Remaining Active Incidents"));
        assertTrue(contract.contains("## Flow Elements"));
        assertTrue(contract.contains("## Child Processes"));
    }

    @Test
    void rendersIncidentResolutionReportDeterministicallyWithAllResolvedChildren() {
        String payload = """
                {
                  "processInstanceKey": "2251799814137577",
                  "status": "SUCCESS",
                  "message": "Incident resolution completed.",
                  "resolutionCommandAttempts": 1,
                  "verificationChecks": 3,
                  "incidentsBeforeResolution": [
                    {
                      "incidentKey": "2251799814137600",
                      "processDefinitionId": "subProcess_InventorySystem",
                      "state": "ACTIVE",
                      "errorType": "JOB_NO_RETRIES",
                      "errorMessage": "inventory failure"
                    }
                  ],
                  "remainingIncidents": [],
                  "postResolutionDiagnostics": {
                    "processInstance": {
                      "processInstanceKey": "2251799814137577",
                      "processDefinitionId": "handleOrderId",
                      "state": "ACTIVE"
                    },
                    "activeIncidents": [],
                    "runtimeVariables": [
                      { "name": "orderId", "value": "ORD-55447" },
                      { "name": "fulfillmentStatus", "value": "COMPLETED_STANDARD" }
                    ],
                    "activeSteps": [
                      { "elementId": "CallActivity_Regular", "elementName": "regular track", "elementType": "CALL_ACTIVITY", "state": "COMPLETED" },
                      { "elementId": "CallActivity_Notification", "elementName": "notification service", "elementType": "CALL_ACTIVITY", "state": "COMPLETED" }
                    ],
                    "childProcessDiagnostics": [
                      {
                        "processInstance": {
                          "processInstanceKey": "2251799814137592",
                          "processDefinitionId": "subProcess_InventorySystem",
                          "state": "COMPLETED"
                        },
                        "activeIncidents": []
                      },
                      {
                        "processInstance": {
                          "processInstanceKey": "2251799814138745",
                          "processDefinitionId": "subProcess_PaymentGateway",
                          "state": "COMPLETED"
                        },
                        "activeIncidents": []
                      },
                      {
                        "processInstance": {
                          "processInstanceKey": "2251799814138773",
                          "processDefinitionId": "regularCategory_ProcessId1",
                          "state": "COMPLETED"
                        },
                        "activeIncidents": []
                      },
                      {
                        "processInstance": {
                          "processInstanceKey": "2251799814138807",
                          "processDefinitionId": "subProcess_NotificationSystem",
                          "state": "COMPLETED"
                        },
                        "activeIncidents": []
                      }
                    ]
                  }
                }
                """;

        String report = reportService.generateReport("conversation-id", "retry incident for ORD-55447", payload, java.util.Optional.empty());

        assertTrue(report.contains("2251799814138807"));
        assertTrue(report.contains("subProcess_NotificationSystem"));
        assertTrue(report.contains("CallActivity_Notification / notification service / CALL_ACTIVITY / COMPLETED"));
        assertFalse(report.contains("[not returned by Camunda]"));
    }
}
