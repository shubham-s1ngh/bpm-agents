package com.shubham.dev.bpm_agent.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.shubham.dev.bpm_agent.camunda.CamundaOrchestrationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class CamundaDiagnosticTools {

    private final CamundaOrchestrationClient clusterClient;
    private static final Logger log = LoggerFactory.getLogger(CamundaDiagnosticTools.class);
    private final int incidentVerificationAttempts;
    private final long incidentVerificationDelayMs;

    @Autowired
    public CamundaDiagnosticTools(CamundaOrchestrationClient clusterClient,
                                  @Value("${app.camunda.incident-verification.attempts:8}") int incidentVerificationAttempts,
                                  @Value("${app.camunda.incident-verification.delay-ms:500}") long incidentVerificationDelayMs) {
        this.clusterClient = clusterClient;
        this.incidentVerificationAttempts = incidentVerificationAttempts;
        this.incidentVerificationDelayMs = incidentVerificationDelayMs;
    }

    @Tool(
            name = "searchProcessInstances",
            description = "Queries the Camunda cluster for high-level process instances using a specific variable key and value filter."
    )
    public List<Map<String, Object>> searchProcessInstances(
            @ToolParam(description = "The process variable name to query, e.g., 'orderId'") String variableName,
            @ToolParam(description = "The exact search value matching the criteria, e.g., 'ORD-55421'") String variableValue) throws JsonProcessingException {
        log.info("[TOOL CALL] searchProcessInstances invoked for Key: {}, Value: {}", variableName, variableValue);
        return clusterClient.searchProcessInstancesByVariable(variableName.trim(), variableValue.trim(), 50);
    }

    @Tool(
            name = "fetchVariablesForInstance",
            description = "Queries the cluster to fetch all active variable key-value pairs belonging to a single specific processInstanceKey string."
    )
    public List<Map<String, Object>> fetchVariablesForInstance(
            @ToolParam(description = "The 16-digit process instance unique tracking key string") String processInstanceKey) throws JsonProcessingException {
        log.info("[TOOL CALL] fetchVariablesForInstance for Key: '{}'", processInstanceKey);
        Long key = Long.parseLong(processInstanceKey.trim());
        return clusterClient.searchVariablesByInstanceKey(key, 50);
    }
    @Tool(
            name = "diagnoseProcessInstance",
            description = "Compiles comprehensive low-level telemetry for a specific process instance key including active cluster incidents, runtime variables, and historic elements blocks."
    )
    public Map<String, Object> diagnoseProcessInstance(
            @ToolParam(description = "The long integer numeric tracking key of the process instance") Long processInstanceKey) {
        log.info("[TOOL CALL] diagnoseProcessInstance invoked for Key: {}", processInstanceKey);
        try {
            if (processInstanceKey == null || processInstanceKey == 0L) {
                return Map.of("status", "Failed", "error", "Invalid instance key signature.");
            }

            return diagnoseProcessInstanceTree(processInstanceKey, 0, new HashSet<>());
        } catch (Exception e) {
            log.error("[TOOL CRITICAL ERROR] diagnoseProcessInstance failed for Key: {}. Error: {}", processInstanceKey, e.getMessage());
            return Map.of(
                    "processInstanceKey", processInstanceKey,
                    "processInstance", Map.of(),
                    "activeIncidents", List.of(),
                    "runtimeVariables", List.of(),
                    "activeSteps", List.of(),
                    "childProcessInstances", List.of(),
                    "childProcessDiagnostics", List.of(),
                    "status", "Failed",
                    "error", e.getMessage()
            );
        }
    }

    private Map<String, Object> diagnoseProcessInstanceTree(Long processInstanceKey, int depth, Set<Long> visitedKeys) throws JsonProcessingException {
        if (depth > 5 || visitedKeys.contains(processInstanceKey)) {
            return Map.of(
                    "processInstanceKey", processInstanceKey,
                    "status", "Skipped",
                    "reason", "Traversal depth limit reached or cycle detected."
            );
        }

        visitedKeys.add(processInstanceKey);

        List<Map<String, Object>> processInstanceMatches = clusterClient.searchProcessInstancesByInstanceKey(processInstanceKey, 1);
        Map<String, Object> processInstance = processInstanceMatches.isEmpty() ? Map.of() : processInstanceMatches.getFirst();
        List<Map<String, Object>> incidents = filterActiveIncidents(clusterClient.getIncidents(processInstanceKey));
        List<Map<String, Object>> variables = clusterClient.searchVariablesByInstanceKey(processInstanceKey, 100);
        List<Map<String, Object>> steps = clusterClient.getFlowNodes(processInstanceKey);
        List<Map<String, Object>> childInstances = clusterClient.searchChildProcessInstances(processInstanceKey, 100);
        List<Map<String, Object>> childDiagnostics = new ArrayList<>();

        for (Map<String, Object> childInstance : childInstances) {
            Long childProcessInstanceKey = parseLongValue(childInstance.get("processInstanceKey"));
            if (childProcessInstanceKey != null) {
                childDiagnostics.add(diagnoseProcessInstanceTree(childProcessInstanceKey, depth + 1, visitedKeys));
            }
        }

        log.info("[TOOL RESULT] Diagnostics compiled for instance {}. Incidents: {}, Variables: {}, Steps: {}, Children: {}",
                processInstanceKey, incidents.size(), variables.size(), steps.size(), childInstances.size());

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("processInstanceKey", processInstanceKey);
        diagnostics.put("processInstance", processInstance);
        diagnostics.put("activeIncidents", incidents);
        diagnostics.put("runtimeVariables", variables);
        diagnostics.put("activeSteps", steps);
        diagnostics.put("childProcessInstances", childInstances);
        diagnostics.put("childProcessDiagnostics", childDiagnostics);
        diagnostics.put("status", "Success");
        return diagnostics;
    }

    private List<Map<String, Object>> filterActiveIncidents(List<Map<String, Object>> incidents) {
        if (incidents == null || incidents.isEmpty()) {
            return List.of();
        }

        return incidents.stream()
                .filter(Objects::nonNull)
                .filter(this::isActiveIncident)
                .toList();
    }

    private boolean isActiveIncident(Map<String, Object> incident) {
        String state = Objects.toString(incident.get("state"), "");
        return "ACTIVE".equalsIgnoreCase(state) || "OPEN".equalsIgnoreCase(state);
    }

    private Long parseLongValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @Tool(
            name = "resolveIncidentByKey",
            description = "Resolves one specific active incident by its incident key using the Camunda incident resolution API."
    )
    public Map<String, Object> resolveIncidentByKey(
            @ToolParam(description = "The unique numeric tracking key string of the specific incident to resolve") String incidentKey) {
        log.info("[MUTATION TOOL CALL] resolveIncidentByKey invoked for Incident Key: {}", incidentKey);
        try {
            String normalizedIncidentKey = incidentKey.trim();
            Map<String, Object> incident = clusterClient.getIncidentByKey(normalizedIncidentKey);
            if (incident.isEmpty()) {
                return Map.of(
                        "status", "FAILED",
                        "error", "Incident was not returned by Camunda for key " + normalizedIncidentKey + ". It may already be resolved, removed, or belong to a different cluster state."
                );
            }

            String incidentState = Objects.toString(incident.get("state"), "");
            if (!"ACTIVE".equalsIgnoreCase(incidentState) && !"OPEN".equalsIgnoreCase(incidentState)) {
                return Map.of(
                        "status", "FAILED",
                        "error", "Incident " + normalizedIncidentKey + " is not retryable because Camunda returned state '" + incidentState + "'.",
                        "incident", incident
                );
            }

            String endpointPath = "/incidents/" + normalizedIncidentKey + "/resolution";
            clusterClient.executeMutationPost(endpointPath, Map.of());

            IncidentPollResult incidentPollResult = pollIncidentUntilStable(normalizedIncidentKey);
            Map<String, Object> postResolutionIncident = incidentPollResult.incident();
            if (!postResolutionIncident.isEmpty()) {
                String postResolutionState = Objects.toString(postResolutionIncident.get("state"), "");
                if ("ACTIVE".equalsIgnoreCase(postResolutionState) || "OPEN".equalsIgnoreCase(postResolutionState)) {
                    return Map.of(
                            "status", "FAILED",
                            "message", "Camunda accepted the incident resolution command, but the incident is still active after verification.",
                            "incident", postResolutionIncident,
                            "resolutionCommandAttempts", 1,
                            "verificationChecks", incidentPollResult.verificationChecks()
                    );
                }
                return Map.of(
                        "status", "PARTIAL_SUCCESS",
                        "message", "Camunda accepted the incident resolution command. The incident record still exists but is no longer active.",
                        "incident", postResolutionIncident,
                        "resolutionCommandAttempts", 1,
                        "verificationChecks", incidentPollResult.verificationChecks()
                );
            }

            log.info("[MUTATION TOOL SUCCESS] Incident resolution transmitted cleanly to Camunda cluster for incident {}", normalizedIncidentKey);
            return Map.of(
                    "status", "SUCCESS",
                    "message", "Incident resolution instruction processed by Camunda and the incident is no longer returned by the cluster.",
                    "incident", incident,
                    "resolutionCommandAttempts", 1,
                    "verificationChecks", incidentPollResult.verificationChecks()
            );
        } catch (Exception e) {
            log.error("[MUTATION TOOL ERROR] Failed to resolve incident {}: {}", incidentKey, e.getMessage());
            return Map.of("status", "FAILED", "error", e.getMessage());
        }
    }

    @Tool(
            name = "resolveIncidentsByProcessInstance",
            description = "Resolves the current active incidents for a given process instance key using the Camunda process instance incident resolution API."
    )
    public Map<String, Object> resolveIncidentsByProcessInstance(
            @ToolParam(description = "The process instance key whose current active incidents should be resolved") String processInstanceKey) {
        log.info("[MUTATION TOOL CALL] resolveIncidentsByProcessInstance invoked for Process Instance Key: {}", processInstanceKey);
        try {
            Long normalizedProcessInstanceKey = Long.parseLong(processInstanceKey.trim());
            ProcessIncidentSnapshot incidentsBeforeResolution = collectActiveIncidentsAcrossTree(normalizedProcessInstanceKey);
            if (incidentsBeforeResolution.isEmpty()) {
                Map<String, Object> currentDiagnostics = diagnoseProcessInstanceTree(normalizedProcessInstanceKey, 0, new HashSet<>());
                return Map.of(
                        "status", "NO_ACTION",
                        "message", "No active incidents were returned by Camunda for processInstanceKey " + normalizedProcessInstanceKey + " or its child process instances.",
                        "processInstanceKey", normalizedProcessInstanceKey,
                        "postResolutionDiagnostics", currentDiagnostics,
                        "resolutionCommandAttempts", 0,
                        "verificationChecks", 1
                );
            }

            int resolutionCommandAttempts = 0;
            for (Long targetProcessInstanceKey : incidentsBeforeResolution.processInstanceKeysWithActiveIncidents()) {
                clusterClient.resolveIncidentsByProcessInstance(targetProcessInstanceKey);
                resolutionCommandAttempts++;
            }

            IncidentTreePollResult incidentTreePollResult = pollActiveIncidentsAcrossTreeUntilStable(normalizedProcessInstanceKey);
            ProcessIncidentSnapshot incidentsAfterResolution = incidentTreePollResult.snapshot();

            if (!incidentsAfterResolution.isEmpty()) {
                Map<String, Object> postResolutionDiagnostics = diagnoseProcessInstanceTree(normalizedProcessInstanceKey, 0, new HashSet<>());
                return Map.of(
                        "status", "FAILED",
                        "message", "Camunda accepted the process-instance incident resolution command, but active incidents still remain in the process tree after verification.",
                        "processInstanceKey", normalizedProcessInstanceKey,
                        "incidentsBeforeResolution", incidentsBeforeResolution.incidents(),
                        "affectedProcessInstanceKeys", incidentsBeforeResolution.processInstanceKeysWithActiveIncidents(),
                        "remainingIncidents", incidentsAfterResolution.incidents(),
                        "remainingProcessInstanceKeys", incidentsAfterResolution.processInstanceKeysWithActiveIncidents(),
                        "postResolutionDiagnostics", postResolutionDiagnostics,
                        "resolutionCommandAttempts", resolutionCommandAttempts,
                        "verificationChecks", incidentTreePollResult.verificationChecks()
                );
            }

            Map<String, Object> postResolutionDiagnostics = diagnoseProcessInstanceTree(normalizedProcessInstanceKey, 0, new HashSet<>());
            return Map.of(
                    "status", "SUCCESS",
                    "message", "Process-instance incident resolution instruction processed by Camunda and no active incidents remain for this process instance or its child process instances.",
                    "processInstanceKey", normalizedProcessInstanceKey,
                    "resolvedIncidents", incidentsBeforeResolution.incidents(),
                    "affectedProcessInstanceKeys", incidentsBeforeResolution.processInstanceKeysWithActiveIncidents(),
                    "postResolutionDiagnostics", postResolutionDiagnostics,
                    "resolutionCommandAttempts", resolutionCommandAttempts,
                    "verificationChecks", incidentTreePollResult.verificationChecks()
            );
        } catch (Exception e) {
            log.error("[MUTATION TOOL ERROR] Failed to resolve incidents for processInstanceKey {}: {}", processInstanceKey, e.getMessage());
            return Map.of("status", "FAILED", "error", e.getMessage());
        }
    }

    private IncidentPollResult pollIncidentUntilStable(String incidentKey) throws JsonProcessingException {
        Map<String, Object> lastIncident = Map.of();
        for (int attempt = 1; attempt <= incidentVerificationAttempts; attempt++) {
            try {
                lastIncident = clusterClient.getIncidentByKey(incidentKey);
            } catch (Exception exception) {
                log.info("[MUTATION VERIFY] Incident {} no longer returned by Camunda on attempt {}", incidentKey, attempt);
                return new IncidentPollResult(Map.of(), attempt);
            }

            String incidentState = Objects.toString(lastIncident.get("state"), "");
            if (!"ACTIVE".equalsIgnoreCase(incidentState) && !"OPEN".equalsIgnoreCase(incidentState)) {
                return new IncidentPollResult(lastIncident, attempt);
            }

            sleepForVerificationWindow();
        }
        return new IncidentPollResult(lastIncident, incidentVerificationAttempts);
    }

    private ProcessIncidentSnapshot collectActiveIncidentsAcrossTree(Long rootProcessInstanceKey) throws JsonProcessingException {
        Set<Long> visitedKeys = new LinkedHashSet<>();
        collectProcessTreeKeys(rootProcessInstanceKey, visitedKeys);
        return collectActiveIncidentsAcrossTree(visitedKeys);
    }

    private ProcessIncidentSnapshot collectActiveIncidentsAcrossTree(Set<Long> processTreeKeys) throws JsonProcessingException {
        List<Map<String, Object>> activeIncidents = new ArrayList<>();
        List<Long> processInstanceKeysWithActiveIncidents = new ArrayList<>();
        for (Long processInstanceKey : processTreeKeys) {
            List<Map<String, Object>> incidents = filterActiveIncidents(clusterClient.getIncidents(processInstanceKey));
            if (!incidents.isEmpty()) {
                activeIncidents.addAll(incidents);
                processInstanceKeysWithActiveIncidents.add(processInstanceKey);
            }
        }

        return new ProcessIncidentSnapshot(
                List.copyOf(activeIncidents),
                List.copyOf(processInstanceKeysWithActiveIncidents)
        );
    }

    private void collectProcessTreeKeys(Long processInstanceKey, Set<Long> visitedKeys) throws JsonProcessingException {
        if (processInstanceKey == null || !visitedKeys.add(processInstanceKey)) {
            return;
        }

        List<Map<String, Object>> childInstances = clusterClient.searchChildProcessInstances(processInstanceKey, 100);
        for (Map<String, Object> childInstance : childInstances) {
            Long childProcessInstanceKey = parseLongValue(childInstance.get("processInstanceKey"));
            if (childProcessInstanceKey != null) {
                collectProcessTreeKeys(childProcessInstanceKey, visitedKeys);
            }
        }
    }

    private IncidentTreePollResult pollActiveIncidentsAcrossTreeUntilStable(Long processInstanceKey) throws JsonProcessingException {
        Set<Long> processTreeKeys = new LinkedHashSet<>();
        collectProcessTreeKeys(processInstanceKey, processTreeKeys);
        ProcessIncidentSnapshot remainingIncidents = new ProcessIncidentSnapshot(List.of(), List.of());
        for (int attempt = 1; attempt <= incidentVerificationAttempts; attempt++) {
            remainingIncidents = collectActiveIncidentsAcrossTree(processTreeKeys);
            if (remainingIncidents.isEmpty()) {
                return new IncidentTreePollResult(remainingIncidents, attempt);
            }
            sleepForVerificationWindow();
        }
        return new IncidentTreePollResult(remainingIncidents, incidentVerificationAttempts);
    }

    private void sleepForVerificationWindow() {
        try {
            Thread.sleep(incidentVerificationDelayMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            log.warn("[MUTATION VERIFY] Verification polling interrupted.");
        }
    }

    private record IncidentPollResult(Map<String, Object> incident, int verificationChecks) {
    }

    private record IncidentTreePollResult(ProcessIncidentSnapshot snapshot, int verificationChecks) {
    }

    private record ProcessIncidentSnapshot(List<Map<String, Object>> incidents,
                                          List<Long> processInstanceKeysWithActiveIncidents) {
        private boolean isEmpty() {
            return incidents == null || incidents.isEmpty();
        }
    }
}
