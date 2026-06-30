package com.shubham.dev.bpm_agent.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shubham.dev.bpm_agent.chat.model.CamundaEvidenceSnapshot;
import com.shubham.dev.bpm_agent.chat.validation.CamundaReportGroundingValidator;
import com.shubham.dev.bpm_agent.strategy.WorkflowContextStrategy;
import com.shubham.dev.bpm_agent.strategy.retrieval.WorkflowKnowledgeVectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Generates the final user-facing markdown report from Camunda evidence.
 *
 * <p>This service owns the report-only model client, prompt construction for the
 * reporting phase, retry behavior after grounding rejection, and final sanitize
 * fallback. It does not execute tools or manage the HTTP layer.</p>
 */
@Service
public class CamundaDiagnosticReportService {

    private static final Logger log = LoggerFactory.getLogger(CamundaDiagnosticReportService.class);

    private final ObjectMapper objectMapper;
    private final ChatClient.Builder chatClientBuilder;
    private final CamundaEvidenceDigestService evidenceDigestService;
    private final CamundaReportGroundingValidator groundingValidator;
    private final WorkflowKnowledgeVectorStoreService workflowKnowledgeVectorStoreService;

    public CamundaDiagnosticReportService(ObjectMapper objectMapper,
                                          ChatClient.Builder chatClientBuilder,
                                          CamundaEvidenceDigestService evidenceDigestService,
                                          CamundaReportGroundingValidator groundingValidator,
                                          WorkflowKnowledgeVectorStoreService workflowKnowledgeVectorStoreService) {
        this.objectMapper = objectMapper;
        this.chatClientBuilder = chatClientBuilder;
        this.evidenceDigestService = evidenceDigestService;
        this.groundingValidator = groundingValidator;
        this.workflowKnowledgeVectorStoreService = workflowKnowledgeVectorStoreService;
    }

    public String generateReport(String conversationId,
                                 String userPrompt,
                                 String diagnosticPayload,
                                 Optional<WorkflowContextStrategy> strategyOpt) {
        CamundaEvidenceSnapshot snapshot = evidenceDigestService.buildSnapshot(diagnosticPayload);
        boolean incidentResolutionPayload = isIncidentResolutionPayload(snapshot);
        if (incidentResolutionPayload) {
            return renderDeterministicIncidentResolutionReport(diagnosticPayload);
        }

        String dynamicReportContract = strategyOpt.map(WorkflowContextStrategy::generateReportStructuringInstructions)
                .orElse("");
        String retrievedWorkflowKnowledge = workflowKnowledgeVectorStoreService.fetchRelevantContext(userPrompt, strategyOpt);
        String stableReportContract = buildStableReportContract(incidentResolutionPayload);

        ChatClient reportChatClient = chatClientBuilder
                .defaultSystem("""
                        You are generating the final user-facing diagnostic report.
                        Return markdown only.
                        Do not return JSON.
                        Do not return tool calls.
                        Do not suggest another tool invocation.
                        Use only the evidence provided in the user message.
                        Follow the exact report structure and heading order requested in the user message.
                        Do not switch between tables and bullets across runs.
                        """)
                .build();

        String groundingRules = """
                HARD GROUNDING CONTRACT:
                - You are a slave reporter over the Camunda evidence digest below.
                - Use only process instance keys, process definition IDs, states, variables, incidents, and flow elements present in CANONICAL CAMUNDA EVIDENCE DIGEST.
                - Do not invent process IDs, child process names, instance keys, variable names, variable values, statuses, incidents, logs, or remediation.
                - If a field is missing from the evidence digest, say it was not returned by Camunda. Do not fill placeholders.
                - `ACTIVE` means running or waiting.
                - `COMPLETED` means finished successfully and is not currently running.
                - `TERMINATED` means stopped or cancelled.
                - Distinguish the root process instance's direct incident count from the total process-tree incident count when child subprocesses have active incidents.
                - Include remediation only when Camunda returned active incidents.
                - Inspect child process evidence recursively when explaining active child processes.
                - Prefer quoting the exact incident error text from the evidence digest when incidents exist.
                - Do not add headings beyond the required report contract.
                - Do not use markdown tables unless the report contract explicitly requires a table.
                """;

        String reportPrompt = String.format("""
                The user asked: %s

                CANONICAL CAMUNDA EVIDENCE DIGEST:
                %s

                %s

                RETRIEVED WORKFLOW KNOWLEDGE:
                %s

                STABLE REPORT CONTRACT:
                %s

                WORKFLOW-SPECIFIC INTERPRETATION RULES:
                %s
                """, userPrompt, snapshot.digest(), groundingRules, retrievedWorkflowKnowledge, stableReportContract, dynamicReportContract);

        String generatedReport = "";
        for (int attempt = 1; attempt <= 2; attempt++) {
            generatedReport = stripMarkdownFence(reportChatClient.prompt()
                    .user(reportPrompt)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId + "-report"))
                    .options(OllamaChatOptions.builder().format((String) null).build())
                    .call()
                    .content());

            List<String> validationErrors = groundingValidator.validate(generatedReport, snapshot);
            if (validationErrors.isEmpty()) {
                return generatedReport;
            }

            log.warn("[REPORT GROUNDING REJECTED] Attempt {} failed: {}", attempt, validationErrors);
            reportPrompt = String.format("""
                    Your previous answer was rejected because it contained data not present in the Camunda payload.

                    REJECTION REASONS:
                    %s

                    Rewrite the report. Remove every rejected value. Use only the CANONICAL CAMUNDA EVIDENCE DIGEST.
                    Keep the exact heading order and markdown structure from the STABLE REPORT CONTRACT.

                    CANONICAL CAMUNDA EVIDENCE DIGEST:
                    %s

                    %s
                    
                    STABLE REPORT CONTRACT:
                    %s
                    """, String.join("\n", validationErrors), snapshot.digest(), groundingRules, stableReportContract);
        }

        log.warn("[REPORT GROUNDING FALLBACK] Returning sanitized report after repeated validation failures.");
        return groundingValidator.sanitize(generatedReport, snapshot);
    }

    String buildStableReportContract(boolean incidentResolutionPayload) {
        if (incidentResolutionPayload) {
            return """
                    Return markdown with this exact heading order:
                    1. `# Diagnostic Report`
                    2. `## Resolution Outcome`
                    3. `## Process Instance Overview`
                    4. `## Active Incidents Before Resolution`
                    5. `## Remaining Active Incidents` only when remaining incidents exist
                    6. `## Variables` only if variables were returned by Camunda evidence
                    7. `## Flow Elements` only if flow elements were returned by Camunda evidence
                    8. `## Child Processes` only if child processes were returned by Camunda evidence

                    Formatting rules:
                    - Use bullets, not tables.
                    - Use one bullet per fact.
                    - Under `## Resolution Outcome`, include:
                      - resolution status
                      - resolution command attempts
                      - verification checks
                    - Include incident state explicitly for every incident shown.
                    - Do not include remediation steps unless active incidents remain after resolution.
                    - If no remaining active incidents exist, say that explicitly under `## Resolution Outcome`.
                    """;
        }

        return """
                Return markdown with this exact heading order:
                1. `# Diagnostic Report`
                2. `## Process Instance Overview`
                3. `## Variables`
                4. `## Flow Elements`
                5. `## Child Processes` only when child processes exist
                6. `## Active Incidents` only when active incidents exist

                Formatting rules:
                - Use bullets, not tables.
                - Use one bullet per fact.
                - Use the same heading names exactly as written above.
                - Under `## Process Instance Overview`, include both the direct root incident count and the total process-tree incident count when the evidence digest provides them.
                - Do not add sections such as `Camunda State Semantics`, `Process Overview`, `Routing Interpretation`, or `Remediation Action`.
                - Explain routing facts inline under the relevant section instead of creating a separate heading.
                - Include incident state explicitly for every incident shown.
                """;
    }

    boolean isIncidentResolutionPayload(CamundaEvidenceSnapshot snapshot) {
        return snapshot.digest().contains("Operation type: incident resolution");
    }

    String renderDeterministicIncidentResolutionReport(String diagnosticPayload) {
        try {
            JsonNode payload = objectMapper.readTree(diagnosticPayload);
            JsonNode postResolutionDiagnostics = payload.path("postResolutionDiagnostics");
            JsonNode rootDiagnostic = postResolutionDiagnostics.isObject() ? postResolutionDiagnostics : payload;
            JsonNode processInstance = rootDiagnostic.path("processInstance");

            StringBuilder report = new StringBuilder("# Diagnostic Report\n\n");
            report.append("## Resolution Outcome\n");
            appendBullet(report, "Resolution Status", evidenceDigestService.firstText(payload, "status"));
            appendBullet(report, "Resolution Command Attempts", evidenceDigestService.firstText(payload, "resolutionCommandAttempts"));
            appendBullet(report, "Verification Checks", evidenceDigestService.firstText(payload, "verificationChecks"));

            String policyMode = evidenceDigestService.firstText(payload, "policyMode");
            if (StringUtils.hasText(policyMode)) {
                appendBullet(report, "Policy Mode", policyMode);
            }

            String message = evidenceDigestService.firstText(payload, "message", "error");
            if (StringUtils.hasText(message)) {
                appendBullet(report, "Resolution Message", message);
            }

            String policyReason = evidenceDigestService.firstText(payload, "policyReason");
            if (StringUtils.hasText(policyReason)) {
                appendBullet(report, "Policy Reason", policyReason);
            }

            String policyGuidance = evidenceDigestService.firstText(payload, "policyGuidance");
            if (StringUtils.hasText(policyGuidance)) {
                appendBullet(report, "Policy Guidance", policyGuidance);
            }

            JsonNode remainingIncidents = payload.path("remainingIncidents");
            appendBullet(report, "Remaining Active Incidents",
                    remainingIncidents.isArray() ? String.valueOf(remainingIncidents.size()) : "0");
            report.append('\n');

            report.append("## Process Instance Overview\n");
            appendBullet(report, "Process Instance Key",
                    evidenceDigestService.firstText(processInstance, "processInstanceKey", "key",
                            "rootProcessInstanceKey"));
            appendBullet(report, "Process Definition ID",
                    evidenceDigestService.firstText(processInstance, "processDefinitionId"));
            appendBullet(report, "State", evidenceDigestService.firstText(processInstance, "state"));
            appendBullet(report, "Incident Count",
                    String.valueOf(rootDiagnostic.path("activeIncidents").isArray()
                            ? rootDiagnostic.path("activeIncidents").size()
                            : 0));
            report.append('\n');

            report.append("## Active Incidents Before Resolution\n");
            JsonNode incidentsBeforeResolution = payload.path("incidentsBeforeResolution");
            if (!incidentsBeforeResolution.isArray()) {
                incidentsBeforeResolution = payload.path("resolvedIncidents");
            }
            if (!incidentsBeforeResolution.isArray()) {
                incidentsBeforeResolution = payload.path("incidents");
            }
            appendIncidentList(report, incidentsBeforeResolution);
            report.append('\n');

            if (remainingIncidents.isArray() && !remainingIncidents.isEmpty()) {
                report.append("## Remaining Active Incidents\n");
                appendIncidentList(report, remainingIncidents);
                report.append('\n');
            }

            appendVariablesSection(report, rootDiagnostic.path("runtimeVariables"));
            appendFlowElementsSection(report, rootDiagnostic.path("activeSteps"));
            appendChildProcessesSection(report, rootDiagnostic.path("childProcessDiagnostics"));

            return report.toString().trim();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to render deterministic incident report", e);
        }
    }

    private String stripMarkdownFence(String modelOutput) {
        String trimmed = modelOutput.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLineEnd >= 0 && lastFence > firstLineEnd) {
                return trimmed.substring(firstLineEnd + 1, lastFence).trim();
            }
        }
        return modelOutput;
    }

    private void appendIncidentList(StringBuilder report, JsonNode incidents) {
        if (!incidents.isArray() || incidents.isEmpty()) {
            report.append("- No incidents were returned by Camunda for this section.\n");
            return;
        }

        for (JsonNode incident : incidents) {
            appendBullet(report, "Incident Key", evidenceDigestService.firstText(incident, "key", "incidentKey"));
            appendBullet(report, "Process Definition ID", evidenceDigestService.firstText(incident, "processDefinitionId"));
            appendBullet(report, "State", evidenceDigestService.firstText(incident, "state"));
            appendBullet(report, "Error Type", evidenceDigestService.firstText(incident, "errorType"));
            appendBullet(report, "Error Message", evidenceDigestService.firstText(incident, "errorMessage", "message"));
            report.append('\n');
        }
    }

    private void appendVariablesSection(StringBuilder report, JsonNode variables) {
        if (!variables.isArray() || variables.isEmpty()) {
            return;
        }

        report.append("## Variables\n");
        for (JsonNode variable : variables) {
            report.append("- **")
                    .append(evidenceDigestService.firstText(variable, "name", "variableName"))
                    .append(":** ")
                    .append(evidenceDigestService.firstText(variable, "value", "variableValue"))
                    .append('\n');
        }
        report.append('\n');
    }

    private void appendFlowElementsSection(StringBuilder report, JsonNode steps) {
        if (!steps.isArray() || steps.isEmpty()) {
            return;
        }

        report.append("## Flow Elements\n");
        for (JsonNode step : steps) {
            report.append("- **Element ")
                    .append(evidenceDigestService.firstText(step, "elementId", "flowNodeId"))
                    .append(" / ")
                    .append(evidenceDigestService.firstText(step, "elementName", "flowNodeName", "name"))
                    .append(" / ")
                    .append(evidenceDigestService.firstText(step, "elementType", "flowNodeType", "type"))
                    .append(" / ")
                    .append(evidenceDigestService.firstText(step, "state"))
                    .append("**\n");
        }
        report.append('\n');
    }

    private void appendChildProcessesSection(StringBuilder report, JsonNode children) {
        if (!children.isArray() || children.isEmpty()) {
            return;
        }

        report.append("## Child Processes\n");
        for (JsonNode child : children) {
            appendChildProcess(report, child, 0);
            report.append('\n');
        }
    }

    private void appendChildProcess(StringBuilder report, JsonNode childDiagnostic, int depth) {
        JsonNode processInstance = childDiagnostic.path("processInstance");
        String indent = "  ".repeat(depth);
        String nestedIndent = "  ".repeat(depth + 1);
        report.append(indent)
                .append("- **Child Instance Key:** ")
                .append(evidenceDigestService.firstText(processInstance, "processInstanceKey", "key"))
                .append('\n');
        report.append(nestedIndent)
                .append("- **Process Definition ID:** ")
                .append(evidenceDigestService.firstText(processInstance, "processDefinitionId"))
                .append('\n');
        report.append(nestedIndent)
                .append("- **State:** ")
                .append(evidenceDigestService.firstText(processInstance, "state"))
                .append('\n');
        report.append(nestedIndent)
                .append("- **Incident Count:** ")
                .append(childDiagnostic.path("activeIncidents").isArray()
                        ? childDiagnostic.path("activeIncidents").size()
                        : 0)
                .append('\n');

        JsonNode grandchildren = childDiagnostic.path("childProcessDiagnostics");
        if (grandchildren.isArray()) {
            for (JsonNode grandchild : grandchildren) {
                appendChildProcess(report, grandchild, depth + 1);
            }
        }
    }

    private void appendBullet(StringBuilder report, String label, String value) {
        report.append("- **").append(label).append(":** ");
        if (StringUtils.hasText(value)) {
            report.append(value);
        } else {
            report.append("not returned by Camunda");
        }
        report.append('\n');
    }
}
