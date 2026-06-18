package com.shubham.dev.bpm_agent.chat.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shubham.dev.bpm_agent.chat.model.CamundaEvidenceSnapshot;
import com.shubham.dev.bpm_agent.chat.service.CamundaEvidenceDigestService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CamundaReportGroundingValidatorTest {

    private final CamundaEvidenceDigestService evidenceDigestService =
            new CamundaEvidenceDigestService(new ObjectMapper());
    private final CamundaReportGroundingValidator validator =
            new CamundaReportGroundingValidator(evidenceDigestService);

    @Test
    void rejectsNoIncidentClaimWhenEvidenceContainsIncident() {
        CamundaEvidenceSnapshot snapshot = evidenceDigestService.buildSnapshot("""
                {
                  "processInstance": {
                    "processInstanceKey": "2251799813819420",
                    "processDefinitionId": "handleOrderId",
                    "state": "ACTIVE"
                  },
                  "activeIncidents": [
                    { "key": "2251799813820051", "errorType": "JOB_ERROR", "errorMessage": "payment failed" }
                  ],
                  "runtimeVariables": [],
                  "activeSteps": [],
                  "childProcessDiagnostics": []
                }
                """);

        List<String> errors = validator.validate("""
                ### Process Instance Key: 2251799813819420
                **State:** ACTIVE
                #### Active Incidents: None
                """, snapshot);

        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(error -> error.contains("no incidents")));
    }

    @Test
    void sanitizesUnknownIdentifiers() {
        CamundaEvidenceSnapshot snapshot = evidenceDigestService.buildSnapshot("""
                {
                  "processInstance": {
                    "processInstanceKey": "2251799813819420",
                    "processDefinitionId": "handleOrderId",
                    "state": "COMPLETED"
                  },
                  "activeIncidents": [],
                  "runtimeVariables": [],
                  "activeSteps": [],
                  "childProcessDiagnostics": []
                }
                """);

        String sanitized = validator.sanitize("""
                Process Instance Key: 2251799813819420
                Process Definition ID: subProcess_Order
                Child Key: 1234567890123456
                """, snapshot);

        assertTrue(sanitized.contains("2251799813819420"));
        assertTrue(sanitized.contains("[not returned by Camunda]"));
    }
}
