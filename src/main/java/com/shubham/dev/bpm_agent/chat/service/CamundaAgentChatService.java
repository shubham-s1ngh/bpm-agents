package com.shubham.dev.bpm_agent.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.shubham.dev.bpm_agent.chat.CamundaDiagnosticTools;
import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionContext;
import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionDecision;
import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionMode;
import com.shubham.dev.bpm_agent.strategy.WorkflowContextStrategy;
import com.shubham.dev.bpm_agent.strategy.WorkflowStrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;

/**
 * Orchestrates the end-to-end chat session for Camunda diagnostics.
 *
 * <p>This service coordinates workflow strategy resolution, execution-model
 * prompting, tool dispatch, short-circuit diagnosis routing, and final report
 * generation. It is the application service behind the HTTP controller.</p>
 */
@Service
public class CamundaAgentChatService {

    private static final Logger log = LoggerFactory.getLogger(CamundaAgentChatService.class);

    private final ChatClient.Builder chatClientBuilder;
    private final CamundaDiagnosticTools diagnosticTools;
    private final WorkflowStrategyRegistry strategyRegistry;
    private final CamundaToolDispatchService toolDispatchService;
    private final CamundaDiagnosticReportService diagnosticReportService;
    private final ObjectMapper objectMapper;

    private final InMemoryChatMemoryRepository memoryRepository = new InMemoryChatMemoryRepository();
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(memoryRepository)
            .maxMessages(30)
            .build();

    public CamundaAgentChatService(ChatClient.Builder chatClientBuilder,
                                   CamundaDiagnosticTools diagnosticTools,
                                   WorkflowStrategyRegistry strategyRegistry,
                                   CamundaToolDispatchService toolDispatchService,
                                   CamundaDiagnosticReportService diagnosticReportService,
                                   ObjectMapper objectMapper) {
        this.chatClientBuilder = chatClientBuilder;
        this.diagnosticTools = diagnosticTools;
        this.strategyRegistry = strategyRegistry;
        this.toolDispatchService = toolDispatchService;
        this.diagnosticReportService = diagnosticReportService;
        this.objectMapper = objectMapper;
    }

    public String handlePrompt(String prompt) {
        String conversationId = UUID.randomUUID().toString();
        log.info("[DYNAMIC AGENT SESSION INIT] Thread token: {}", conversationId);
        boolean allowRetryIncident = isExplicitRetryIntent(prompt);
        Optional<String> directIncidentKey = allowRetryIncident ? extractRequestedIncidentKey(prompt) : Optional.empty();

        Optional<WorkflowContextStrategy> strategyOpt = strategyRegistry.getStrategyForPrompt(prompt);
        String bpmnContext = strategyOpt.map(WorkflowContextStrategy::generateBpmnContextInstructions).orElse("");
        String businessIdentifierInstructions = strategyOpt.map(WorkflowContextStrategy::generateBusinessIdentifierInstructions).orElse("");
        Map<String, String> translations = strategyOpt.map(s -> s.translateVariables(prompt)).orElse(Map.of());
        String selectedProcessId = strategyOpt.map(WorkflowContextStrategy::getProcessDefinitionId).orElse("UNKNOWN");
        log.info("[WORKFLOW STRATEGY] Selected processId: {}", selectedProcessId);

        ChatClient executionChatClient = chatClientBuilder
                .defaultSystem(buildExecutionSystemInstructions(selectedProcessId, bpmnContext, businessIdentifierInstructions, translations, allowRetryIncident))
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();

        try {
            if (allowRetryIncident && directIncidentKey.isPresent()) {
                log.info("[DIRECT MUTATION ROUTE] Explicit retry intent with incidentKey: {}", directIncidentKey.get());
                return resolveIncidentByKeyReport(conversationId, prompt, strategyOpt, directIncidentKey.get());
            }

            if (allowRetryIncident && isBulkBusinessIdentifierRetryPrompt(prompt, strategyOpt)) {
                WorkflowContextStrategy strategy = strategyOpt.orElseThrow();
                List<String> requestedBusinessIdentifiers = strategy.extractBusinessIdentifiers(prompt);
                if (requestedBusinessIdentifiers.size() > 1) {
                    log.info("[BULK MUTATION ROUTE] Explicit retry intent with {} {} identifiers: {}",
                            requestedBusinessIdentifiers.size(),
                            strategy.primaryBusinessIdentifierVariable(),
                            requestedBusinessIdentifiers);
                    return handleBulkBusinessIdentifierIncidentRetry(conversationId, prompt, strategy, requestedBusinessIdentifiers);
                }
            }

            Optional<Long> directProcessInstanceKey = extractRequestedProcessInstanceKey(prompt, allowRetryIncident);
            if (allowRetryIncident && directProcessInstanceKey.isPresent()) {
                log.info("[DIRECT MUTATION ROUTE] Explicit retry intent with processInstanceKey: {}", directProcessInstanceKey.get());
                return resolveIncidentsByProcessInstanceWithPolicyReport(
                        conversationId,
                        prompt,
                        strategyOpt,
                        directProcessInstanceKey.get()
                );
            }

            if (directProcessInstanceKey.isPresent()) {
                log.info("[DIRECT DIAGNOSTIC ROUTE] processInstanceKey isolated from prompt: {}", directProcessInstanceKey.get());
                String diagnosticPayload = toolDispatchService.serialize(diagnosticTools.diagnoseProcessInstance(directProcessInstanceKey.get()));
                return diagnosticReportService.generateReport(conversationId, prompt, diagnosticPayload, strategyOpt);
            }

            return runExecutionLoop(prompt, conversationId, strategyOpt, executionChatClient, allowRetryIncident);
        } finally {
            memoryRepository.deleteByConversationId(conversationId);
            log.info("[DYNAMIC AGENT SESSION COMPLETE] Memory repository cleared for thread: {}", conversationId);
        }
    }

    private String runExecutionLoop(String prompt,
                                    String conversationId,
                                    Optional<WorkflowContextStrategy> strategyOpt,
                                    ChatClient executionChatClient,
                                    boolean allowRetryIncident) {
        String currentPromptInput = prompt;
        String finalAnswer = "";
        boolean forceTextMode = false;
        Set<String> executedToolsInTurn = new HashSet<>();

        for (int step = 0; step < 8; step++) {
            log.info("[AGENT LOOP - STEP {}] Dispatching active execution context stream to Qwen...", step);

            ChatOptions runtimeOptions = forceTextMode
                    ? OllamaChatOptions.builder().format(null).build()
                    : OllamaChatOptions.builder().format("json").build();

            String modelOutput = executionChatClient.prompt()
                    .user(currentPromptInput)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .options(runtimeOptions)
                    .call()
                    .content();

            log.info("[AGENT LOOP - STEP {}] Model Response:\n{}", step, modelOutput);

            if (modelOutput == null || modelOutput.isBlank()) {
                return "Agent engine processing timeout.";
            }

            if (forceTextMode) {
                log.info("[AGENT PROCESS COMPLETE] Text mode response accepted without JSON extraction.");
                return stripMarkdownFence(modelOutput);
            }

            String extractedJsonToolCall = toolDispatchService.extractJsonFromText(modelOutput);
            if (extractedJsonToolCall == null) {
                log.info("[AGENT PROCESS COMPLETE] Pure conversational non-JSON response isolated.");
                finalAnswer = modelOutput;
                break;
            }

            if (executedToolsInTurn.contains(extractedJsonToolCall)) {
                log.warn("[LOOP INTERCEPTED AT STEP {}] Model is repeating a tool call. Forcing text mode.", step);
                forceTextMode = true;
                currentPromptInput = currentPromptInput + "\n\nCRITICAL SYSTEM DIRECTIVE: You have already fetched the data. Output your final text summary report using clean markdown tables now.";
                continue;
            }

            executedToolsInTurn.add(extractedJsonToolCall);
            log.info("[PROXY EXECUTIVE] Extracted JSON tool action on step {}. Executing function...", step);
            String executedToolName = toolDispatchService.extractToolName(extractedJsonToolCall);
            if (("resolveIncidentByKey".equals(executedToolName) || "resolveIncidentsByProcessInstance".equals(executedToolName))
                    && !allowRetryIncident) {
                log.warn("[MUTATION BLOCKED] Incident resolution blocked because prompt did not contain explicit retry intent.");
                return "Incident resolution operations are blocked unless the user explicitly asks to retry or resolve incidents.";
            }

            String toolResultPayload = toolDispatchService.runTool(extractedJsonToolCall, allowRetryIncident);
            log.info("[PROXY EXECUTIVE] Tool result payload on step {}: {}", step, toolResultPayload);

            if ("[]".equals(toolResultPayload.trim())) {
                return "No Camunda process instances were returned for the requested identifier. Check the application logs for the exact `/process-instances/search` payload sent to Camunda.";
            }

            if ("searchProcessInstances".equals(executedToolName)) {
                String followUpActionResult = handleSearchProcessInstancesResult(toolResultPayload, allowRetryIncident, conversationId, prompt, strategyOpt);
                if (followUpActionResult != null) {
                    return followUpActionResult;
                }
            }

            if (toolResultPayload.contains("\"activeSteps\"") || toolResultPayload.contains("\"activeIncidents\"")) {
                log.info("[SHORT-CIRCUIT ACTIVATED] Deep metrics collected. Fetching dynamic strategy reporting schemas...");
                return diagnosticReportService.generateReport(conversationId, prompt, toolResultPayload, strategyOpt);
            }

            currentPromptInput = String.format("""
                    [LIVE_DATA_METRICS]
                    RAW TELEMETRY RETURNED FROM CAMUNDA CLUSTER RUNTIMES:
                    %s

                    DIRECTIONS: Evaluate this payload data. To pull deep error logs and stack traces for an isolated processInstanceKey, execute your `diagnoseProcessInstance` tool now.
                    """, toolResultPayload);
        }

        if (!StringUtils.hasText(finalAnswer)) {
            return "No final agent answer was produced. Check the application logs for the last model tool call and Camunda API response.";
        }
        return finalAnswer;
    }

    private String buildExecutionSystemInstructions(String selectedProcessId,
                                                    String bpmnContext,
                                                    String businessIdentifierInstructions,
                                                    Map<String, String> translations,
                                                    boolean allowRetryIncident) {
        return String.format("""
                You are a strict Camunda 8 API Executor. You are forbidden from inventing or hallucinating tool names.
                You must only use the EXACT tool names listed below. If you use any other name, the system will crash.

                SELECTED WORKFLOW PROCESS ID:
                %s

                BPMN WORKFLOW LOGIC CONTEXT:
                %s

                WORKFLOW BUSINESS IDENTIFIER CONTEXT:
                %s

                SUGGESTED MAPPINGS: %s

                STRICT STEP-BY-STEP LOOKUP PROTOCOL:
                1. To look up a workflow trace by any identifier provided by the user, your ABSOLUTE FIRST STEP must be to call `searchProcessInstances`.
                2. Once `searchProcessInstances` returns a payload:
                   - Look inside the `items` array. Find the item where `state` is exactly "ACTIVE".
                   - Extract its `processInstanceKey`.
                   - You must IMMEDIATELY execute a subsequent tool call targeting `diagnoseProcessInstance` using that explicit active key.
                   - Do not output a high-level table summary list of past completed or terminated records if an active incident key can be isolated.
                3. Analyze the returned deep diagnostics payload. Extract the incident details and present them as a critical error.
                4. `resolveIncidentByKey` and `resolveIncidentsByProcessInstance` are mutation tools. Use them only when the user explicitly asks to retry or resolve incidents.

                EXCLUSIVE ALLOWED TOOL ALIGNMENT MATRIX (USE EXACT NAMES):
                - {"name": "searchProcessInstances", "arguments": {"variableName": "VARIABLE_NAME_HERE", "variableValue": "VALUE_HERE"}}
                - {"name": "fetchVariablesForInstance", "arguments": {"processInstanceKey": "2251799813819420"}}
                - {"name": "diagnoseProcessInstance", "arguments": {"processInstanceKey": 2251799813819420}}
                %s

                STRICT OUTPUT LIMITATION:
                Your output must be a single raw JSON block matching one of the schemas above. Do not include conversational thoughts, introductions, or warnings.
                """, selectedProcessId, bpmnContext, businessIdentifierInstructions, translations,
                allowRetryIncident
                        ? """
                          - {"name": "resolveIncidentByKey", "arguments": {"incidentKey": "2251799813820051"}}
                          - {"name": "resolveIncidentsByProcessInstance", "arguments": {"processInstanceKey": "2251799813819420"}}
                          """
                        : "- incident resolution tools are unavailable because the user did not explicitly request a retry");
    }

    public String resolveIncidentByKey(String incidentKey) {
        if (!StringUtils.hasText(incidentKey)) {
            return "incidentKey must not be empty.";
        }
        return toolDispatchService.serialize(diagnosticTools.resolveIncidentByKey(incidentKey));
    }

    public String resolveIncidentsByProcessInstance(String processInstanceKey) {
        if (!StringUtils.hasText(processInstanceKey)) {
            return "processInstanceKey must not be empty.";
        }
        return toolDispatchService.serialize(diagnosticTools.resolveIncidentsByProcessInstance(processInstanceKey));
    }

    Optional<Long> extractRequestedProcessInstanceKey(String prompt, boolean allowBareRetryTarget) {
        Pattern explicitPattern = Pattern.compile("(?i)process\\s*instance\\s*key\\D+(\\d{15,20})|processInstanceKey\\D+(\\d{15,20})");
        Matcher explicitMatcher = explicitPattern.matcher(prompt);
        if (explicitMatcher.find()) {
            String key = explicitMatcher.group(1) != null ? explicitMatcher.group(1) : explicitMatcher.group(2);
            return Optional.of(Long.parseLong(key));
        }

        if (!allowBareRetryTarget) {
            return Optional.empty();
        }

        Matcher bareLongNumberMatcher = Pattern.compile("\\b(\\d{15,20})\\b").matcher(prompt);
        Long extractedKey = null;
        while (bareLongNumberMatcher.find()) {
            long candidate = Long.parseLong(bareLongNumberMatcher.group(1));
            if (extractedKey != null && extractedKey != candidate) {
                return Optional.empty();
            }
            extractedKey = candidate;
        }
        if (extractedKey != null) {
            return Optional.of(extractedKey);
        }
        return Optional.empty();
    }

    Optional<String> extractRequestedIncidentKey(String prompt) {
        Pattern explicitPattern = Pattern.compile("(?i)incident\\s*key\\D+(\\d{15,20})|incidentKey\\D+(\\d{15,20})");
        Matcher explicitMatcher = explicitPattern.matcher(prompt);
        if (explicitMatcher.find()) {
            return Optional.of(explicitMatcher.group(1) != null ? explicitMatcher.group(1) : explicitMatcher.group(2));
        }
        return Optional.empty();
    }

    private Optional<Long> extractActiveProcessInstanceKey(String toolResultPayload) {
        try {
            JsonNode instances = objectMapper.readTree(toolResultPayload);
            if (!instances.isArray()) {
                return Optional.empty();
            }

            Long fallbackKey = null;
            for (JsonNode instance : instances) {
                long processInstanceKey = instance.path("processInstanceKey").asLong(0L);
                if (processInstanceKey == 0L) {
                    continue;
                }
                if (fallbackKey == null) {
                    fallbackKey = processInstanceKey;
                }
                if ("ACTIVE".equalsIgnoreCase(instance.path("state").asText())) {
                    return Optional.of(processInstanceKey);
                }
            }
            return fallbackKey == null ? Optional.empty() : Optional.of(fallbackKey);
        } catch (Exception e) {
            log.warn("[DIAGNOSTIC ROUTE WARNING] Could not parse process search result: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String handleBulkBusinessIdentifierIncidentRetry(String conversationId,
                                                             String prompt,
                                                             WorkflowContextStrategy strategy,
                                                             List<String> businessIdentifiers) {
        List<BulkOrderRetryResult> results = new ArrayList<>();
        String selectedProcessId = strategy.getProcessDefinitionId();
        String identifierVariable = strategy.primaryBusinessIdentifierVariable();

        for (String businessIdentifier : businessIdentifiers) {
            try {
                String searchPayload = toolDispatchService.serialize(
                        diagnosticTools.searchProcessInstances(identifierVariable, businessIdentifier)
                );
                Optional<Long> activeProcessInstanceKey = extractActiveProcessInstanceKey(searchPayload);
                if (activeProcessInstanceKey.isEmpty()) {
                    results.add(BulkOrderRetryResult.notFound(businessIdentifier));
                    continue;
                }

                String diagnosticPayload = toolDispatchService.serialize(
                        diagnosticTools.diagnoseProcessInstance(activeProcessInstanceKey.get())
                );
                JsonNode diagnostic = objectMapper.readTree(diagnosticPayload);
                List<Map<String, Object>> activeIncidents = collectActiveIncidents(diagnostic);

                IncidentResolutionContext resolutionContext = new IncidentResolutionContext(
                        prompt,
                        selectedProcessId,
                        collectProcessDefinitionIds(diagnostic),
                        activeProcessInstanceKey.get(),
                        activeIncidents,
                        objectMapper.convertValue(diagnostic, Map.class)
                );

                IncidentResolutionDecision decision = strategy.buildResolutionDecision(resolutionContext);

                if (!decision.allowed()) {
                    results.add(BulkOrderRetryResult.fromDecision(businessIdentifier, activeProcessInstanceKey.get(), decision));
                    continue;
                }

                if (decision.mode() == IncidentResolutionMode.BY_INCIDENT_KEY) {
                    Optional<String> incidentKey = activeIncidents.stream()
                            .map(incident -> firstNonBlank(
                                    String.valueOf(incident.getOrDefault("key", "")),
                                    String.valueOf(incident.getOrDefault("incidentKey", ""))
                            ))
                            .filter(StringUtils::hasText)
                            .findFirst();

                    if (incidentKey.isEmpty()) {
                        results.add(BulkOrderRetryResult.failed(
                                businessIdentifier,
                                "Workflow strategy requested incident-key resolution, but no active incident key was available."
                        ));
                        continue;
                    }

                    String mutationPayload = resolveIncidentByKey(incidentKey.get());
                    JsonNode mutation = objectMapper.readTree(mutationPayload);
                    results.add(BulkOrderRetryResult.fromMutation(businessIdentifier, activeProcessInstanceKey.get(), mutation, decision));
                    continue;
                }

                String mutationPayload = resolveIncidentsByProcessInstance(String.valueOf(activeProcessInstanceKey.get()));
                JsonNode mutation = objectMapper.readTree(mutationPayload);
                results.add(BulkOrderRetryResult.fromMutation(businessIdentifier, activeProcessInstanceKey.get(), mutation, decision));
            } catch (Exception exception) {
                results.add(BulkOrderRetryResult.failed(businessIdentifier, exception.getMessage()));
            }
        }

        return buildBulkBusinessIdentifierRetryReport(identifierVariable, results);
    }

    private String buildBulkBusinessIdentifierRetryReport(String identifierVariable, List<BulkOrderRetryResult> results) {
        long succeeded = results.stream().filter(result -> "SUCCESS".equals(result.status())).count();
        long noAction = results.stream().filter(result -> "NO_ACTION".equals(result.status())).count();
        long blocked = results.stream().filter(result -> "BLOCKED".equals(result.status())).count();
        long failed = results.stream().filter(result -> "FAILED".equals(result.status())).count();
        long notFound = results.stream().filter(result -> "NOT_FOUND".equals(result.status())).count();

        StringBuilder report = new StringBuilder();
        report.append("# Bulk Incident Retry Report").append('\n').append('\n');
        report.append("## Summary").append('\n');
        report.append("- Requested ").append(identifierVariable).append(" count: ").append(results.size()).append('\n');
        report.append("- Successful retries: ").append(succeeded).append('\n');
        report.append("- No-action results: ").append(noAction).append('\n');
        report.append("- Blocked retries: ").append(blocked).append('\n');
        report.append("- Failed retries: ").append(failed).append('\n');
        report.append("- Identifiers not found: ").append(notFound).append('\n').append('\n');
        report.append("## Per Identifier").append('\n');

        for (BulkOrderRetryResult result : results) {
            report.append("- ").append(identifierVariable).append(": ").append(result.orderId()).append('\n');
            if (result.processInstanceKey() != null) {
                report.append("  Process instance key: ").append(result.processInstanceKey()).append('\n');
            }
            report.append("  Status: ").append(result.status()).append('\n');
            if (StringUtils.hasText(result.policyReason())) {
                report.append("  Policy reason: ").append(result.policyReason()).append('\n');
            }
            if (StringUtils.hasText(result.policyMode())) {
                report.append("  Policy mode: ").append(result.policyMode()).append('\n');
            }
            if (StringUtils.hasText(result.message())) {
                report.append("  Message: ").append(result.message()).append('\n');
            }
            if (result.resolutionCommandAttempts() != null) {
                report.append("  Resolution command attempts: ").append(result.resolutionCommandAttempts()).append('\n');
            }
            if (result.verificationChecks() != null) {
                report.append("  Verification checks: ").append(result.verificationChecks()).append('\n');
            }
        }

        return report.toString();
    }

    private List<Map<String, Object>> collectActiveIncidents(JsonNode diagnostic) {
        List<Map<String, Object>> activeIncidents = new ArrayList<>();
        collectActiveIncidents(diagnostic, activeIncidents);
        return List.copyOf(activeIncidents);
    }

    private void collectActiveIncidents(JsonNode diagnostic, List<Map<String, Object>> activeIncidents) {
        if (diagnostic == null || !diagnostic.isObject()) {
            return;
        }

        JsonNode incidents = diagnostic.path("activeIncidents");
        if (incidents.isArray()) {
            for (JsonNode incident : incidents) {
                activeIncidents.add(objectMapper.convertValue(incident, Map.class));
            }
        }

        JsonNode childDiagnostics = diagnostic.path("childProcessDiagnostics");
        if (childDiagnostics.isArray()) {
            for (JsonNode childDiagnostic : childDiagnostics) {
                collectActiveIncidents(childDiagnostic, activeIncidents);
            }
        }
    }

    String handleSearchProcessInstancesResult(String toolResultPayload,
                                              boolean allowRetryIncident,
                                              String conversationId,
                                              String prompt,
                                              Optional<WorkflowContextStrategy> strategyOpt) {
        Optional<Long> activeProcessInstanceKey = extractActiveProcessInstanceKey(toolResultPayload);
        if (activeProcessInstanceKey.isEmpty()) {
            return null;
        }

        if (allowRetryIncident) {
            log.info("[DETERMINISTIC MUTATION ROUTE] Explicit retry intent with active processInstanceKey isolated from search: {}", activeProcessInstanceKey.get());
            return resolveIncidentsByProcessInstanceWithPolicyReport(
                    conversationId,
                    prompt,
                    strategyOpt,
                    activeProcessInstanceKey.get()
            );
        }

        log.info("[DETERMINISTIC DIAGNOSTIC ROUTE] Active processInstanceKey isolated: {}", activeProcessInstanceKey.get());
        String diagnosticPayload = toolDispatchService.serialize(diagnosticTools.diagnoseProcessInstance(activeProcessInstanceKey.get()));
        return diagnosticReportService.generateReport(conversationId, prompt, diagnosticPayload, strategyOpt);
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

    private boolean isExplicitRetryIntent(String prompt) {
        String normalized = prompt.toLowerCase();
        return normalized.contains("retry incident")
                || normalized.contains("retry this incident")
                || normalized.contains("retry the incident")
                || normalized.contains("please retry")
                || normalized.contains("resume this incident")
                || normalized.matches(".*\\bretry\\b.*\\bincident\\b.*");
    }

    private boolean isBulkBusinessIdentifierRetryPrompt(String prompt, Optional<WorkflowContextStrategy> strategyOpt) {
        return strategyOpt
                .map(strategy -> strategy.extractBusinessIdentifiers(prompt).size() > 1)
                .orElse(false);
    }

    private String resolveIncidentByKeyReport(String conversationId,
                                              String prompt,
                                              Optional<WorkflowContextStrategy> strategyOpt,
                                              String incidentKey) {
        String mutationPayload = resolveIncidentByKey(incidentKey);
        return diagnosticReportService.generateReport(conversationId, prompt, mutationPayload, strategyOpt);
    }

    private String resolveIncidentsByProcessInstanceReport(String conversationId,
                                                           String prompt,
                                                           Optional<WorkflowContextStrategy> strategyOpt,
                                                           String processInstanceKey) {
        String mutationPayload = resolveIncidentsByProcessInstance(processInstanceKey);
        return diagnosticReportService.generateReport(conversationId, prompt, mutationPayload, strategyOpt);
    }

    private String resolveIncidentsByProcessInstanceWithPolicyReport(String conversationId,
                                                                     String prompt,
                                                                     Optional<WorkflowContextStrategy> strategyOpt,
                                                                     Long processInstanceKey) {
        if (strategyOpt.isEmpty()) {
            return resolveIncidentsByProcessInstanceReport(conversationId, prompt, Optional.empty(), String.valueOf(processInstanceKey));
        }

        try {
            String diagnosticPayload = toolDispatchService.serialize(diagnosticTools.diagnoseProcessInstance(processInstanceKey));
            JsonNode diagnostic = objectMapper.readTree(diagnosticPayload);
            List<Map<String, Object>> activeIncidents = collectActiveIncidents(diagnostic);

            WorkflowContextStrategy strategy = strategyOpt.get();
            IncidentResolutionContext resolutionContext = new IncidentResolutionContext(
                    prompt,
                    strategy.getProcessDefinitionId(),
                    collectProcessDefinitionIds(diagnostic),
                    processInstanceKey,
                    activeIncidents,
                    objectMapper.convertValue(diagnostic, Map.class)
            );

            IncidentResolutionDecision decision = strategy.buildResolutionDecision(resolutionContext);
            if (!decision.allowed()) {
                String policyPayload = objectMapper.writeValueAsString(
                        buildSingleProcessResolutionPolicyPayload(processInstanceKey, activeIncidents, diagnostic, decision)
                );
                return diagnosticReportService.generateReport(conversationId, prompt, policyPayload, strategyOpt);
            }

            if (decision.mode() == IncidentResolutionMode.BY_INCIDENT_KEY) {
                Optional<String> incidentKey = activeIncidents.stream()
                        .map(incident -> firstNonBlank(
                                String.valueOf(incident.getOrDefault("key", "")),
                                String.valueOf(incident.getOrDefault("incidentKey", ""))
                        ))
                        .filter(StringUtils::hasText)
                        .findFirst();

                if (incidentKey.isPresent()) {
                    return resolveIncidentByKeyReport(conversationId, prompt, strategyOpt, incidentKey.get());
                }
                String policyPayload = objectMapper.writeValueAsString(
                        buildSingleProcessResolutionPolicyPayload(
                                processInstanceKey,
                                activeIncidents,
                                diagnostic,
                                IncidentResolutionDecision.blocked(
                                "Workflow strategy requested incident-key resolution, but no active incident key was available.",
                                "Retry is blocked because the workflow policy required an incident key and the active incident payload did not provide one."
                                )
                        )
                );
                return diagnosticReportService.generateReport(conversationId, prompt, policyPayload, strategyOpt);
            }

            return resolveIncidentsByProcessInstanceReport(conversationId, prompt, strategyOpt, String.valueOf(processInstanceKey));
        } catch (Exception exception) {
            return "Failed to evaluate workflow retry policy before incident resolution: " + exception.getMessage();
        }
    }

    private Map<String, Object> buildSingleProcessResolutionPolicyPayload(Long processInstanceKey,
                                                                          List<Map<String, Object>> activeIncidents,
                                                                          JsonNode diagnostic,
                                                                          IncidentResolutionDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("processInstanceKey", processInstanceKey);
        payload.put("status", decision.mode().name());
        payload.put("message", firstNonBlank(decision.userFacingGuidance(), decision.reason()));
        payload.put("policyReason", decision.reason());
        payload.put("policyGuidance", decision.userFacingGuidance());
        payload.put("resolutionCommandAttempts", 0);
        payload.put("verificationChecks", 0);
        payload.put("incidentsBeforeResolution", activeIncidents);
        payload.put("remainingIncidents", decision.mode() == IncidentResolutionMode.NO_ACTION ? List.of() : activeIncidents);
        payload.put("postResolutionDiagnostics", objectMapper.convertValue(diagnostic, Map.class));
        return payload;
    }

    private Set<String> collectProcessDefinitionIds(JsonNode diagnostic) {
        Set<String> processDefinitionIds = new LinkedHashSet<>();
        collectProcessDefinitionIds(diagnostic, processDefinitionIds);
        return Set.copyOf(processDefinitionIds);
    }

    private void collectProcessDefinitionIds(JsonNode diagnostic, Set<String> processDefinitionIds) {
        if (diagnostic == null || !diagnostic.isObject()) {
            return;
        }

        String processDefinitionId = diagnostic.path("processInstance").path("processDefinitionId").asText("");
        if (StringUtils.hasText(processDefinitionId)) {
            processDefinitionIds.add(processDefinitionId);
        }

        JsonNode childDiagnostics = diagnostic.path("childProcessDiagnostics");
        if (childDiagnostics.isArray()) {
            for (JsonNode childDiagnostic : childDiagnostics) {
                collectProcessDefinitionIds(childDiagnostic, processDefinitionIds);
            }
        }
    }

    private record BulkOrderRetryResult(String orderId,
                                        Long processInstanceKey,
                                        String status,
                                        String policyMode,
                                        String policyReason,
                                        String message,
                                        Integer resolutionCommandAttempts,
                                        Integer verificationChecks) {
        private static BulkOrderRetryResult notFound(String orderId) {
            return new BulkOrderRetryResult(
                    orderId,
                    null,
                    "NOT_FOUND",
                    null,
                    null,
                    "No Camunda process instance was returned for this order ID.",
                    null,
                    null
            );
        }

        private static BulkOrderRetryResult failed(String orderId, String message) {
            return new BulkOrderRetryResult(orderId, null, "FAILED", null, null, message, null, null);
        }

        private static BulkOrderRetryResult fromDecision(String orderId,
                                                         Long processInstanceKey,
                                                         IncidentResolutionDecision decision) {
            return new BulkOrderRetryResult(
                    orderId,
                    processInstanceKey,
                    decision.mode().name(),
                    decision.mode().name(),
                    decision.reason(),
                    firstNonBlank(decision.userFacingGuidance(), decision.reason()),
                    0,
                    0
            );
        }

        private static BulkOrderRetryResult fromMutation(String orderId,
                                                         Long processInstanceKey,
                                                         JsonNode mutation,
                                                         IncidentResolutionDecision decision) {
            return new BulkOrderRetryResult(
                    orderId,
                    processInstanceKey,
                    mutation.path("status").asText("FAILED"),
                    decision.mode().name(),
                    decision.reason(),
                    firstNonBlank(
                            mutation.path("message").asText(),
                            mutation.path("error").asText()
                    ),
                    mutation.has("resolutionCommandAttempts") ? mutation.path("resolutionCommandAttempts").asInt() : null,
                    mutation.has("verificationChecks") ? mutation.path("verificationChecks").asInt() : null
            );
        }

        private static String firstNonBlank(String first, String second) {
            return StringUtils.hasText(first) ? first : second;
        }
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }
}
