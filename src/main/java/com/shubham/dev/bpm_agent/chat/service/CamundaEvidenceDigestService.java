package com.shubham.dev.bpm_agent.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shubham.dev.bpm_agent.chat.model.CamundaEvidenceSnapshot;
import com.shubham.dev.bpm_agent.chat.model.CamundaInstanceEvidence;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts raw Camunda diagnostic JSON into normalized evidence structures.
 *
 * <p>The digest service has one responsibility: interpret the diagnostic payload
 * returned by Camunda tools and derive canonical evidence for downstream use by
 * the reporting and grounding layers.</p>
 */
@Service
public class CamundaEvidenceDigestService {

    private final ObjectMapper objectMapper;

    public CamundaEvidenceDigestService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CamundaEvidenceSnapshot buildSnapshot(String diagnosticPayload) {
        try {
            JsonNode evidence = objectMapper.readTree(diagnosticPayload);
            Set<String> allowedNumbers = new HashSet<>();
            Set<String> allowedProcessLikeIdentifiers = new HashSet<>();
            Map<String, CamundaInstanceEvidence> instancesByKey = new LinkedHashMap<>();
            collectEvidenceTokens(evidence, allowedNumbers, allowedProcessLikeIdentifiers);
            collectInstanceEvidence(evidence, instancesByKey);

            StringBuilder digest = new StringBuilder();
            appendEvidenceDigest(evidence, digest, 0);

            return new CamundaEvidenceSnapshot(
                    digest.toString(),
                    allowedNumbers,
                    allowedProcessLikeIdentifiers,
                    instancesByKey
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse Camunda diagnostic payload", e);
        }
    }

    public boolean hasAnyIncidents(CamundaEvidenceSnapshot snapshot) {
        return snapshot.instancesByKey().values().stream().anyMatch(instance -> instance.incidentCount() > 0);
    }

    public String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asText();
            }
        }
        return "";
    }

    private void appendEvidenceDigest(JsonNode diagnostic, StringBuilder digest, int depth) {
        if (isIncidentResolutionPayload(diagnostic)) {
            appendIncidentResolutionDigest(diagnostic, digest);
            return;
        }

        JsonNode processInstance = diagnostic.path("processInstance");
        String prefix = depth == 0 ? "" : "Child ".repeat(depth);
        digest.append(prefix)
                .append("Instance key: ").append(firstText(processInstance, "processInstanceKey")).append('\n')
                .append(prefix).append("Process definition ID: ").append(firstText(processInstance, "processDefinitionId")).append('\n');
        String processDefinitionName = firstText(processInstance, "processDefinitionName");
        if (StringUtils.hasText(processDefinitionName)) {
            digest.append(prefix).append("Process definition name: ").append(processDefinitionName).append('\n');
        }
        digest.append(prefix).append("State: ").append(firstText(processInstance, "state")).append('\n');

        JsonNode incidents = diagnostic.path("activeIncidents");
        digest.append(prefix).append("Incident count: ").append(incidents.isArray() ? incidents.size() : 0).append('\n');
        if (incidents.isArray()) {
            for (JsonNode incident : incidents) {
                digest.append(prefix).append("Incident key: ").append(firstText(incident, "key", "incidentKey")).append('\n');
                digest.append(prefix).append("Incident error type: ").append(firstText(incident, "errorType")).append('\n');
                digest.append(prefix).append("Incident error message: ").append(firstText(incident, "errorMessage", "message")).append('\n');
            }
        }

        JsonNode variables = diagnostic.path("runtimeVariables");
        if (variables.isArray()) {
            for (JsonNode variable : variables) {
                digest.append(prefix)
                        .append("Variable ")
                        .append(firstText(variable, "name", "variableName"))
                        .append(" = ")
                        .append(firstText(variable, "value", "variableValue"))
                        .append('\n');
            }
        }

        JsonNode steps = diagnostic.path("activeSteps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                digest.append(prefix)
                        .append("Element ")
                        .append(firstText(step, "elementId", "flowNodeId"))
                        .append(" / ")
                        .append(firstText(step, "elementName", "flowNodeName", "name"))
                        .append(" / ")
                        .append(firstText(step, "elementType", "flowNodeType", "type"))
                        .append(" / ")
                        .append(firstText(step, "state"))
                        .append('\n');
            }
        }

        JsonNode children = diagnostic.path("childProcessDiagnostics");
        if (children.isArray()) {
            for (JsonNode child : children) {
                appendEvidenceDigest(child, digest, depth + 1);
            }
        }
    }

    private void appendIncidentResolutionDigest(JsonNode diagnostic, StringBuilder digest) {
        digest.append("Operation type: incident resolution").append('\n');
        digest.append("Process instance key: ").append(firstText(diagnostic, "processInstanceKey")).append('\n');
        digest.append("Resolution status: ").append(firstText(diagnostic, "status")).append('\n');
        digest.append("Resolution message: ").append(firstText(diagnostic, "message", "error")).append('\n');
        digest.append("Resolution command attempts: ").append(firstText(diagnostic, "resolutionCommandAttempts")).append('\n');
        digest.append("Verification checks: ").append(firstText(diagnostic, "verificationChecks")).append('\n');

        JsonNode incidentsBeforeResolution = diagnostic.path("incidentsBeforeResolution");
        if (incidentsBeforeResolution.isMissingNode() || incidentsBeforeResolution.isNull() || !incidentsBeforeResolution.isArray()) {
            incidentsBeforeResolution = diagnostic.path("resolvedIncidents");
        }
        if (incidentsBeforeResolution.isMissingNode() || incidentsBeforeResolution.isNull() || !incidentsBeforeResolution.isArray()) {
            incidentsBeforeResolution = diagnostic.path("incidents");
        }

        digest.append("Incidents before resolution: ")
                .append(incidentsBeforeResolution.isArray() ? incidentsBeforeResolution.size() : 0)
                .append('\n');
        if (incidentsBeforeResolution.isArray()) {
            for (JsonNode incident : incidentsBeforeResolution) {
                appendIncidentDigestLine(digest, "Before resolution", incident);
            }
        }

        JsonNode remainingIncidents = diagnostic.path("remainingIncidents");
        digest.append("Remaining incidents after resolution: ")
                .append(remainingIncidents.isArray() ? remainingIncidents.size() : 0)
                .append('\n');
        if (remainingIncidents.isArray()) {
            for (JsonNode incident : remainingIncidents) {
                appendIncidentDigestLine(digest, "Remaining incident", incident);
            }
        }

        JsonNode postResolutionDiagnostics = diagnostic.path("postResolutionDiagnostics");
        if (postResolutionDiagnostics.isObject() && !postResolutionDiagnostics.isEmpty()) {
            digest.append("Post-resolution diagnostic snapshot:").append('\n');
            appendEvidenceDigest(postResolutionDiagnostics, digest, 0);
        }
    }

    private void appendIncidentDigestLine(StringBuilder digest, String label, JsonNode incident) {
        digest.append(label).append(" key: ").append(firstText(incident, "key", "incidentKey")).append('\n');
        digest.append(label).append(" process definition ID: ").append(firstText(incident, "processDefinitionId")).append('\n');
        digest.append(label).append(" state: ").append(firstText(incident, "state")).append('\n');
        digest.append(label).append(" error type: ").append(firstText(incident, "errorType")).append('\n');
        digest.append(label).append(" error message: ").append(firstText(incident, "errorMessage", "message")).append('\n');
    }

    private void collectInstanceEvidence(JsonNode diagnostic, Map<String, CamundaInstanceEvidence> instancesByKey) {
        if (diagnostic == null || diagnostic.isMissingNode() || diagnostic.isNull()) {
            return;
        }

        if (isIncidentResolutionPayload(diagnostic)) {
            collectIncidentResolutionEvidence(diagnostic, instancesByKey);
            return;
        }

        JsonNode processInstance = diagnostic.path("processInstance");
        String key = firstText(processInstance, "processInstanceKey");
        if (StringUtils.hasText(key)) {
            instancesByKey.put(key, new CamundaInstanceEvidence(
                    key,
                    firstText(processInstance, "processDefinitionId", "processDefinitionName"),
                    firstText(processInstance, "state"),
                    diagnostic.path("activeIncidents").isArray() ? diagnostic.path("activeIncidents").size() : 0
            ));
        }

        JsonNode children = diagnostic.path("childProcessDiagnostics");
        if (children.isArray()) {
            for (JsonNode child : children) {
                collectInstanceEvidence(child, instancesByKey);
            }
        }
    }

    private void collectIncidentResolutionEvidence(JsonNode diagnostic, Map<String, CamundaInstanceEvidence> instancesByKey) {
        String processInstanceKey = firstText(diagnostic, "processInstanceKey");
        if (!StringUtils.hasText(processInstanceKey)) {
            return;
        }

        JsonNode postResolutionDiagnostics = diagnostic.path("postResolutionDiagnostics");
        if (postResolutionDiagnostics.isObject() && !postResolutionDiagnostics.isEmpty()) {
            collectInstanceEvidence(postResolutionDiagnostics, instancesByKey);
            return;
        }

        JsonNode remainingIncidents = diagnostic.path("remainingIncidents");
        JsonNode referenceIncidents = remainingIncidents;
        if (!referenceIncidents.isArray() || referenceIncidents.isEmpty()) {
            referenceIncidents = diagnostic.path("incidentsBeforeResolution");
        }
        if (!referenceIncidents.isArray() || referenceIncidents.isEmpty()) {
            referenceIncidents = diagnostic.path("resolvedIncidents");
        }
        if (!referenceIncidents.isArray() || referenceIncidents.isEmpty()) {
            referenceIncidents = diagnostic.path("incidents");
        }

        String processDefinitionId = "";
        int activeIncidentCount = remainingIncidents.isArray() ? remainingIncidents.size() : 0;
        if (referenceIncidents.isArray()) {
            if (!referenceIncidents.isEmpty()) {
                processDefinitionId = firstText(referenceIncidents.get(0), "processDefinitionId");
            }
        }

        instancesByKey.put(processInstanceKey, new CamundaInstanceEvidence(
                processInstanceKey,
                processDefinitionId,
                "",
                activeIncidentCount
        ));
    }

    private boolean isIncidentResolutionPayload(JsonNode diagnostic) {
        return diagnostic.isObject()
                && diagnostic.has("status")
                && diagnostic.has("processInstanceKey")
                && (diagnostic.has("incidentsBeforeResolution")
                || diagnostic.has("remainingIncidents")
                || diagnostic.has("resolvedIncidents")
                || diagnostic.has("incidents")
                || diagnostic.has("postResolutionDiagnostics"));
    }

    private void collectEvidenceTokens(JsonNode node, Set<String> allowedNumbers, Set<String> allowedProcessLikeIdentifiers) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isNumber()) {
            allowedNumbers.add(node.asText());
            return;
        }
        if (node.isTextual()) {
            String text = node.asText();
            Matcher numberMatcher = Pattern.compile("\\b\\d{6,}\\b").matcher(text);
            while (numberMatcher.find()) {
                allowedNumbers.add(numberMatcher.group());
            }

            Matcher processLikeMatcher = Pattern.compile("\\b[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+\\b").matcher(text);
            while (processLikeMatcher.find()) {
                allowedProcessLikeIdentifiers.add(processLikeMatcher.group());
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectEvidenceTokens(child, allowedNumbers, allowedProcessLikeIdentifiers));
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectEvidenceTokens(entry.getValue(), allowedNumbers, allowedProcessLikeIdentifiers));
        }
    }
}
