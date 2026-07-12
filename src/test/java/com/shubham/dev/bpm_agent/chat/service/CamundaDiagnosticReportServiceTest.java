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
    void buildsWaitingPointContractForWaitingPrompts() {
        String contract = reportService.buildStableReportContract(
                false,
                reportService.detectReadOnlyReportIntent("where this order id ORD-55448 is waiting"));

        assertTrue(contract.contains("## Current Waiting Point"));
        assertTrue(contract.contains("identify the current active element or active child process"));
        assertFalse(contract.contains("## Current Workflow Path"));
    }

    @Test
    void buildsWorkflowPathContractForPathPrompts() {
        String contract = reportService.buildStableReportContract(
                false,
                reportService.detectReadOnlyReportIntent("explain the current workflow path for order ORD-55448"));

        assertTrue(contract.contains("## Current Workflow Path"));
        assertTrue(contract.contains("summarize the verified completed path and the current active stage"));
        assertFalse(contract.contains("## Current Waiting Point"));
    }

    @Test
    void detectsReadOnlyIntentFromPrompt() {
        assertTrue(reportService.detectReadOnlyReportIntent("where this order id ORD-55448 is waiting")
                == CamundaDiagnosticReportService.ReadOnlyReportIntent.WAITING_POINT);
        assertTrue(reportService.detectReadOnlyReportIntent("explain the current workflow path for order ORD-55448")
                == CamundaDiagnosticReportService.ReadOnlyReportIntent.WORKFLOW_PATH);
        assertTrue(reportService.detectReadOnlyReportIntent("check for this order id ORD-55448")
                == CamundaDiagnosticReportService.ReadOnlyReportIntent.GENERIC);
    }

    @Test
    void rendersDeterministicWaitingPointSummaryWithoutRepeatingDuplicateActiveSteps() {
        String payload = """
                {
                  "processInstance": {
                    "processInstanceKey": "2251799814151644",
                    "processDefinitionId": "handleOrderId",
                    "processDefinitionName": "handle order",
                    "state": "ACTIVE"
                  },
                  "activeIncidents": [],
                  "runtimeVariables": [
                    { "name": "path", "value": "regular" }
                  ],
                  "activeSteps": [
                    { "elementId": "CallActivity_Regular", "elementName": "regular track", "elementType": "CALL_ACTIVITY", "state": "COMPLETED" },
                    { "elementId": "Gateway_Merge", "elementName": "merge", "elementType": "EXCLUSIVE_GATEWAY", "state": "COMPLETED" },
                    { "elementId": "Task_SendEmail", "elementName": "Send Item Dispatch Alert", "elementType": "SERVICE_TASK", "state": "ACTIVE" },
                    { "elementId": "Task_SendEmail", "elementName": "Send Item Dispatch Alert", "elementType": "SERVICE_TASK", "state": "ACTIVE" }
                  ],
                  "childProcessDiagnostics": []
                }
                """;

        String report = reportService.renderDeterministicIntentAwareDiagnosticReport(
                payload,
                CamundaDiagnosticReportService.ReadOnlyReportIntent.WAITING_POINT);

        assertTrue(report.contains("## Current Waiting Point"));
        assertTrue(report.contains("**Current Active Element:** Send Item Dispatch Alert (Task_SendEmail) / SERVICE_TASK / ACTIVE"));
        assertTrue(report.contains("**Parallel Active Executions:** 2"));
        assertTrue(report.contains("**Last Completed Element Before Wait:** merge (Gateway_Merge) / EXCLUSIVE_GATEWAY / COMPLETED"));
        assertFalse(report.contains("Send Item Dispatch Alert / SERVICE_TASK / ACTIVE\n- **Current Active Element:**"));
    }

    @Test
    void rendersDeterministicWorkflowPathSummaryWithCondensedActiveStage() {
        String payload = """
                {
                  "processInstance": {
                    "processInstanceKey": "2251799814151644",
                    "processDefinitionId": "handleOrderId",
                    "processDefinitionName": "handle order",
                    "state": "ACTIVE"
                  },
                  "activeIncidents": [],
                  "runtimeVariables": [
                    { "name": "path", "value": "regular" }
                  ],
                  "activeSteps": [
                    { "elementId": "StartEvent_1", "elementName": "start", "elementType": "START_EVENT", "state": "COMPLETED" },
                    { "elementId": "Activity_1wvpf97", "elementName": "sanitize order payload", "elementType": "SCRIPT_TASK", "state": "COMPLETED" },
                    { "elementId": "CallActivity_Regular", "elementName": "regular track", "elementType": "CALL_ACTIVITY", "state": "COMPLETED" },
                    { "elementId": "Task_SendEmail", "elementName": "Send Item Dispatch Alert", "elementType": "SERVICE_TASK", "state": "ACTIVE" },
                    { "elementId": "Task_SendEmail", "elementName": "Send Item Dispatch Alert", "elementType": "SERVICE_TASK", "state": "ACTIVE" }
                  ],
                  "childProcessDiagnostics": []
                }
                """;

        String report = reportService.renderDeterministicIntentAwareDiagnosticReport(
                payload,
                CamundaDiagnosticReportService.ReadOnlyReportIntent.WORKFLOW_PATH);

        assertTrue(report.contains("## Current Workflow Path"));
        assertTrue(report.contains("**Completed Stages:**"));
        assertTrue(report.contains("1. start [START_EVENT]"));
        assertTrue(report.contains("2. sanitize order payload [SCRIPT_TASK]"));
        assertTrue(report.contains("3. regular track [CALL_ACTIVITY]"));
        assertTrue(report.contains("**Current Active Stage:** Send Item Dispatch Alert [SERVICE_TASK]"));
        assertTrue(report.contains("**Current BPMN Element ID:** Task_SendEmail"));
        assertTrue(report.contains("**Parallel Active Executions:** 2"));
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
