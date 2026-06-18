package com.shubham.dev.bpm_agent.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shubham.dev.bpm_agent.chat.CamundaDiagnosticTools;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses model-emitted tool JSON and dispatches the corresponding diagnostic tool.
 *
 * <p>This service isolates the low-level contract between the execution model
 * and {@link CamundaDiagnosticTools}. Keeping dispatch here prevents the chat
 * orchestrator and controller from depending on JSON parsing details.</p>
 */
@Service
public class CamundaToolDispatchService {

    private final CamundaDiagnosticTools diagnosticTools;
    private final ObjectMapper objectMapper;

    public CamundaToolDispatchService(CamundaDiagnosticTools diagnosticTools, ObjectMapper objectMapper) {
        this.diagnosticTools = diagnosticTools;
        this.objectMapper = objectMapper;
    }

    public String extractJsonFromText(String text) {
        if (text == null) {
            return null;
        }
        Pattern pattern = Pattern.compile("\\{.*\\}", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group().trim();
        }
        return null;
    }

    public String extractToolName(String cleanJson) {
        try {
            return objectMapper.readTree(cleanJson).path("name").asText();
        } catch (Exception ignored) {
            return "";
        }
    }

    public String runTool(String cleanJson) {
        return runTool(cleanJson, true);
    }

    public String runTool(String cleanJson, boolean allowRetryIncident) {
        try {
            JsonNode jsonNode = objectMapper.readTree(cleanJson);
            String targetToolName = jsonNode.path("name").asText();
            JsonNode arguments = jsonNode.path("arguments");

            if ("searchProcessInstances".equals(targetToolName)) {
                return objectMapper.writeValueAsString(diagnosticTools.searchProcessInstances(
                        arguments.path("variableName").asText(),
                        arguments.path("variableValue").asText()
                ));
            }
            if ("fetchVariablesForInstance".equals(targetToolName)) {
                return objectMapper.writeValueAsString(diagnosticTools.fetchVariablesForInstance(
                        arguments.path("processInstanceKey").asText()
                ));
            }
            if ("diagnoseProcessInstance".equals(targetToolName)) {
                JsonNode keyNode = arguments.path("processInstanceKey");
                if (keyNode.isMissingNode() || keyNode.asLong() == 0L) {
                    keyNode = arguments.path("processInstanceId");
                }
                return objectMapper.writeValueAsString(diagnosticTools.diagnoseProcessInstance(keyNode.asLong()));
            }
            if ("resolveIncidentByKey".equals(targetToolName)) {
                if (!allowRetryIncident) {
                    return "{\"error\": \"resolveIncidentByKey is blocked unless the user explicitly requests an incident resolution or retry.\"}";
                }
                return objectMapper.writeValueAsString(diagnosticTools.resolveIncidentByKey(arguments.path("incidentKey").asText()));
            }
            if ("resolveIncidentsByProcessInstance".equals(targetToolName)) {
                if (!allowRetryIncident) {
                    return "{\"error\": \"resolveIncidentsByProcessInstance is blocked unless the user explicitly requests an incident resolution or retry.\"}";
                }
                return objectMapper.writeValueAsString(diagnosticTools.resolveIncidentsByProcessInstance(arguments.path("processInstanceKey").asText()));
            }
            return "{\"error\": \"Unrecognized tool path choice inside agent framework proxy layer.\"}";
        } catch (Exception e) {
            return "{\"error\": \"Failed to resolve tool action: " + e.getMessage() + "\"}";
        }
    }

    public String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"status\":\"Failed\",\"error\":\"Failed to serialize Camunda diagnostic payload.\"}";
        }
    }
}
