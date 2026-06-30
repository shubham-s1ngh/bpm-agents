package com.shubham.dev.bpm_agent.strategy.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BpmnIncidentRuleSuggestionServiceTest {

    private final BpmnIncidentRuleSuggestionService service =
            new BpmnIncidentRuleSuggestionService(mock(ChatClient.Builder.class), new ObjectMapper());

    @Test
    void extractsServiceTaskAndCallActivityCandidatesFromBpmnXml() {
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:zeebe="http://camunda.org/schema/zeebe/1.0">
                  <bpmn:process id="handleLoanApplication" name="Handle Loan Application" isExecutable="true">
                    <bpmn:serviceTask id="Task_AssessRisk" name="Assess Risk">
                      <bpmn:extensionElements>
                        <zeebe:taskDefinition type="risk-assessment"/>
                      </bpmn:extensionElements>
                    </bpmn:serviceTask>
                    <bpmn:callActivity id="Call_VerifyApplicant" name="Verify Applicant" calledElement="subProcess_VerifyApplicant"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        List<BpmnProcessSummary> summaries = service.extractProcessSummaries("loan-parent.bpmn", bpmn);

        assertEquals(1, summaries.size());
        BpmnProcessSummary summary = summaries.getFirst();
        assertEquals("handleLoanApplication", summary.processDefinitionId());
        assertEquals(2, summary.candidates().size());
        assertEquals("risk-assessment", summary.candidates().getFirst().jobType());
        assertEquals("callActivity", summary.candidates().get(1).elementType());
        assertEquals("subProcess_VerifyApplicant", summary.candidates().get(1).calledElement());
    }

    @Test
    void extractsMultipleProcessesFromUploadedXmlBundle() {
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:zeebe="http://camunda.org/schema/zeebe/1.0">
                  <bpmn:process id="loanParent" name="Loan Parent">
                    <bpmn:callActivity id="Call_Payment" name="Call Payment" calledElement="loanPaymentSubprocess"/>
                  </bpmn:process>
                  <bpmn:process id="loanPaymentSubprocess" name="Loan Payment Subprocess">
                    <bpmn:serviceTask id="Task_Charge" name="Charge Fee">
                      <bpmn:extensionElements>
                        <zeebe:taskDefinition type="payment-charge"/>
                      </bpmn:extensionElements>
                    </bpmn:serviceTask>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        List<BpmnProcessSummary> summaries = service.extractProcessSummaries("loan-bundle.bpmn", bpmn);

        assertEquals(2, summaries.size());
        assertTrue(summaries.stream().anyMatch(summary -> summary.processDefinitionId().equals("loanParent")));
        assertTrue(summaries.stream().anyMatch(summary -> summary.processDefinitionId().equals("loanPaymentSubprocess")));
    }
}
