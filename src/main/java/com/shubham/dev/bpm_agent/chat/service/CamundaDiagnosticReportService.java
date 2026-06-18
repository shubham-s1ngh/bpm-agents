package com.shubham.dev.bpm_agent.chat.service;

import com.shubham.dev.bpm_agent.chat.model.CamundaEvidenceSnapshot;
import com.shubham.dev.bpm_agent.chat.validation.CamundaReportGroundingValidator;
import com.shubham.dev.bpm_agent.strategy.WorkflowContextStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;

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

    private final ChatClient.Builder chatClientBuilder;
    private final CamundaEvidenceDigestService evidenceDigestService;
    private final CamundaReportGroundingValidator groundingValidator;

    public CamundaDiagnosticReportService(ChatClient.Builder chatClientBuilder,
                                          CamundaEvidenceDigestService evidenceDigestService,
                                          CamundaReportGroundingValidator groundingValidator) {
        this.chatClientBuilder = chatClientBuilder;
        this.evidenceDigestService = evidenceDigestService;
        this.groundingValidator = groundingValidator;
    }

    public String generateReport(String conversationId,
                                 String userPrompt,
                                 String diagnosticPayload,
                                 Optional<WorkflowContextStrategy> strategyOpt) {
        CamundaEvidenceSnapshot snapshot = evidenceDigestService.buildSnapshot(diagnosticPayload);
        boolean incidentResolutionPayload = isIncidentResolutionPayload(snapshot);
        String dynamicReportContract = strategyOpt.map(WorkflowContextStrategy::generateReportStructuringInstructions)
                .orElse("");
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

                STABLE REPORT CONTRACT:
                %s

                WORKFLOW-SPECIFIC INTERPRETATION RULES:
                %s
                """, userPrompt, snapshot.digest(), groundingRules, stableReportContract, dynamicReportContract);

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
                - Do not add sections such as `Camunda State Semantics`, `Process Overview`, `Routing Interpretation`, or `Remediation Action`.
                - Explain routing facts inline under the relevant section instead of creating a separate heading.
                - Include incident state explicitly for every incident shown.
                """;
    }

    boolean isIncidentResolutionPayload(CamundaEvidenceSnapshot snapshot) {
        return snapshot.digest().contains("Operation type: incident resolution");
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
}
