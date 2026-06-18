package com.shubham.dev.bpm_agent.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shubham.dev.bpm_agent.chat.model.CamundaEvidenceSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CamundaEvidenceDigestServiceTest {

    private final CamundaEvidenceDigestService evidenceDigestService =
            new CamundaEvidenceDigestService(new ObjectMapper());

    @Test
    void buildsSnapshotFromRootAndChildDiagnostics() {
        String payload = """
                {
                  "processInstanceKey": 2251799813824980,
                  "processInstance": {
                    "processInstanceKey": "2251799813824980",
                    "processDefinitionId": "handleOrderId",
                    "processDefinitionName": "handle order",
                    "state": "ACTIVE"
                  },
                  "activeIncidents": [],
                  "runtimeVariables": [
                    { "name": "orderId", "value": "\\"ORD-88742\\"" }
                  ],
                  "activeSteps": [],
                  "childProcessDiagnostics": [
                    {
                      "processInstance": {
                        "processInstanceKey": "2251799813824991",
                        "processDefinitionId": "advanceCategory_processId",
                        "processDefinitionName": "advanced track",
                        "state": "ACTIVE"
                      },
                      "activeIncidents": [
                        { "key": "2251799813825000", "errorType": "JOB_ERROR", "errorMessage": "something failed" }
                      ],
                      "runtimeVariables": [],
                      "activeSteps": [],
                      "childProcessDiagnostics": []
                    }
                  ]
                }
                """;

        CamundaEvidenceSnapshot snapshot = evidenceDigestService.buildSnapshot(payload);

        assertTrue(snapshot.digest().contains("Process definition ID: handleOrderId"));
        assertTrue(snapshot.digest().contains("Process definition name: handle order"));
        assertTrue(snapshot.digest().contains("Process definition ID: advanceCategory_processId"));
        assertEquals("ACTIVE", snapshot.instancesByKey().get("2251799813824980").state());
        assertEquals(1, snapshot.instancesByKey().get("2251799813824991").incidentCount());
        assertTrue(snapshot.allowedNumbers().contains("2251799813825000"));
    }

    @Test
    void buildsSnapshotFromIncidentResolutionPayload() {
        String payload = """
                {
                  "processInstanceKey": 2251799813950818,
                  "status": "FAILED",
                  "message": "Camunda accepted the process-instance incident resolution command, but active incidents still remain after verification.",
                  "resolutionCommandAttempts": 1,
                  "verificationChecks": 4,
                  "incidentsBeforeResolution": [
                    {
                      "processDefinitionId": "handleOrderId",
                      "incidentKey": "2251799813950884",
                      "state": "ACTIVE",
                      "errorType": "CALLED_ELEMENT_ERROR",
                      "errorMessage": "Expected process with BPMN process id 'regularCategory_ProcessId1' to be deployed, but not found."
                    }
                  ],
                  "remainingIncidents": [
                    {
                      "processDefinitionId": "handleOrderId",
                      "incidentKey": "2251799813950884",
                      "state": "ACTIVE",
                      "errorType": "CALLED_ELEMENT_ERROR",
                      "errorMessage": "Expected process with BPMN process id 'regularCategory_ProcessId1' to be deployed, but not found."
                    }
                  ]
                }
                """;

        CamundaEvidenceSnapshot snapshot = evidenceDigestService.buildSnapshot(payload);

        assertTrue(snapshot.digest().contains("Operation type: incident resolution"));
        assertTrue(snapshot.digest().contains("Resolution command attempts: 1"));
        assertTrue(snapshot.digest().contains("Verification checks: 4"));
        assertTrue(snapshot.digest().contains("Remaining incidents after resolution: 1"));
        assertEquals("", snapshot.instancesByKey().get("2251799813950818").state());
        assertEquals(1, snapshot.instancesByKey().get("2251799813950818").incidentCount());
        assertTrue(snapshot.allowedProcessLikeIdentifiers().contains("regularCategory_ProcessId1"));
    }

    @Test
    void buildsSnapshotFromIncidentResolutionPayloadWithPostResolutionDiagnostics() {
        String payload = """
                {
                  "processInstanceKey": 2251799813950818,
                  "status": "SUCCESS",
                  "message": "Process-instance incident resolution instruction processed by Camunda and no active incidents remain for this process instance.",
                  "resolutionCommandAttempts": 1,
                  "verificationChecks": 2,
                  "resolvedIncidents": [
                    {
                      "processDefinitionId": "handleOrderId",
                      "incidentKey": "2251799813954208",
                      "state": "ACTIVE",
                      "errorType": "CALLED_ELEMENT_ERROR",
                      "errorMessage": "Expected process with BPMN process id 'regularCategory_ProcessId1' to be deployed, but not found."
                    }
                  ],
                  "postResolutionDiagnostics": {
                    "processInstanceKey": 2251799813950818,
                    "processInstance": {
                      "processInstanceKey": "2251799813950818",
                      "processDefinitionId": "handleOrderId",
                      "processDefinitionName": "handle order",
                      "state": "ACTIVE"
                    },
                    "activeIncidents": [],
                    "runtimeVariables": [
                      { "name": "orderId", "value": "\\"ORD-55422\\"" }
                    ],
                    "activeSteps": [
                      { "elementId": "CallActivity_Notification", "elementName": "notification service", "elementType": "CALL_ACTIVITY", "state": "ACTIVE" }
                    ],
                    "childProcessDiagnostics": []
                  }
                }
                """;

        CamundaEvidenceSnapshot snapshot = evidenceDigestService.buildSnapshot(payload);

        assertTrue(snapshot.digest().contains("Post-resolution diagnostic snapshot:"));
        assertTrue(snapshot.digest().contains("Resolution command attempts: 1"));
        assertTrue(snapshot.digest().contains("Verification checks: 2"));
        assertTrue(snapshot.digest().contains("Process definition ID: handleOrderId"));
        assertEquals("ACTIVE", snapshot.instancesByKey().get("2251799813950818").state());
        assertEquals(0, snapshot.instancesByKey().get("2251799813950818").incidentCount());
    }

    @Test
    void buildsSnapshotFromNoActionPayloadWithPostResolutionDiagnostics() {
        String payload = """
                {
                  "processInstanceKey": 2251799813950818,
                  "status": "NO_ACTION",
                  "message": "No active incidents were returned by Camunda for processInstanceKey 2251799813950818.",
                  "resolutionCommandAttempts": 0,
                  "verificationChecks": 1,
                  "postResolutionDiagnostics": {
                    "processInstanceKey": 2251799813950818,
                    "processInstance": {
                      "processInstanceKey": "2251799813950818",
                      "processDefinitionId": "handleOrderId",
                      "processDefinitionName": "handle order",
                      "state": "ACTIVE"
                    },
                    "activeIncidents": [],
                    "runtimeVariables": [
                      { "name": "orderId", "value": "\\"ORD-55422\\"" },
                      { "name": "paymentStatus", "value": "\\"SUCCESS\\"" }
                    ],
                    "activeSteps": [
                      { "elementId": "Task_SendEmail", "elementName": "Send Item Dispatch Alert", "elementType": "MULTI_INSTANCE_BODY", "state": "ACTIVE" }
                    ],
                    "childProcessDiagnostics": [
                      {
                        "processInstance": {
                          "processInstanceKey": "2251799813959446",
                          "processDefinitionId": "subProcess_NotificationSystem",
                          "processDefinitionName": "Multi-Instance Notification System",
                          "state": "ACTIVE"
                        },
                        "activeIncidents": [],
                        "runtimeVariables": [],
                        "activeSteps": [
                          { "elementId": "Task_SendEmail", "elementName": "Send Item Dispatch Alert", "elementType": "MULTI_INSTANCE_BODY", "state": "ACTIVE" }
                        ],
                        "childProcessDiagnostics": []
                      }
                    ]
                  }
                }
                """;

        CamundaEvidenceSnapshot snapshot = evidenceDigestService.buildSnapshot(payload);

        assertTrue(snapshot.digest().contains("Operation type: incident resolution"));
        assertTrue(snapshot.digest().contains("Resolution command attempts: 0"));
        assertTrue(snapshot.digest().contains("Verification checks: 1"));
        assertTrue(snapshot.digest().contains("Process definition ID: handleOrderId"));
        assertTrue(snapshot.digest().contains("Variable orderId = \"ORD-55422\""));
        assertEquals("ACTIVE", snapshot.instancesByKey().get("2251799813950818").state());
        assertEquals(0, snapshot.instancesByKey().get("2251799813950818").incidentCount());
        assertEquals("ACTIVE", snapshot.instancesByKey().get("2251799813959446").state());
    }
}
