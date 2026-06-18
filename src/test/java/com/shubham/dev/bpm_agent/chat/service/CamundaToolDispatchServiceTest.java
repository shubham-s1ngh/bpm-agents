package com.shubham.dev.bpm_agent.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shubham.dev.bpm_agent.chat.CamundaDiagnosticTools;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CamundaToolDispatchServiceTest {

    @Test
    void blocksRetryWhenMutationIsNotAllowed() {
        CamundaDiagnosticTools tools = Mockito.mock(CamundaDiagnosticTools.class);
        CamundaToolDispatchService service = new CamundaToolDispatchService(tools, new ObjectMapper());

        String result = service.runTool("""
                {
                  "name": "resolveIncidentByKey",
                  "arguments": {
                    "incidentKey": "2251799813820051"
                  }
                }
                """, false);

        assertTrue(result.contains("blocked unless the user explicitly requests an incident resolution or retry"));
    }
}
