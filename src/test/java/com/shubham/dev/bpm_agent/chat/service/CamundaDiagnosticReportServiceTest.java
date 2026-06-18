package com.shubham.dev.bpm_agent.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shubham.dev.bpm_agent.chat.validation.CamundaReportGroundingValidator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CamundaDiagnosticReportServiceTest {

    private final CamundaEvidenceDigestService evidenceDigestService =
            new CamundaEvidenceDigestService(new ObjectMapper());
    private final CamundaReportGroundingValidator groundingValidator =
            new CamundaReportGroundingValidator(evidenceDigestService);
    private final CamundaDiagnosticReportService reportService =
            new CamundaDiagnosticReportService(mock(ChatClient.Builder.class), evidenceDigestService, groundingValidator);

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
}
