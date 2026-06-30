package com.shubham.dev.bpm_agent.strategy;

import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionContext;
import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionDecision;
import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionMode;
import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionRule;

import java.util.List;
import java.util.Map;

public interface WorkflowContextStrategy {
    /**
     * The unique identifier matching the Camunda processDefinitionId / processId.
     */
    String getProcessDefinitionId();

    /**
     * Determines if this strategy can handle the incoming user prompt.
     */
    boolean isApplicable(String userPrompt);

    /**
     * Generates rich textual context detailing the internal business mappings of the BPMN diagram.
     */
    String generateBpmnContextInstructions();

    /**
     * Translates a human text value (like "advanced") to its Camunda variable variant (like 1).
     */
    Map<String, String> translateVariables(String userPrompt);

    /**
     * Workflow-specific instructions for resolving business identifiers from user language
     * into canonical Camunda variable names and values.
     */
    default String generateBusinessIdentifierInstructions() {
        return "";
    }

    /**
     * The canonical Camunda variable used as the primary workflow lookup identifier.
     */
    default String primaryBusinessIdentifierVariable() {
        return "";
    }

    /**
     * Extracts one or more workflow-specific business identifiers from the user prompt.
     * Strategies own this parsing so generic orchestration does not hardcode identifier formats.
     */
    default List<String> extractBusinessIdentifiers(String userPrompt) {
        return List.of();
    }

    /**
     * Whether this workflow supports incident-resolution operations at all.
     */
    default boolean supportsIncidentResolution() {
        return true;
    }

    /**
     * The strategy-preferred mutation mode when resolution is allowed.
     */
    default IncidentResolutionMode preferredResolutionMode() {
        return IncidentResolutionMode.BY_PROCESS_INSTANCE;
    }

    /**
     * Ordered workflow-specific resolution rules. The first blocked match wins,
     * otherwise the first allowed match wins, otherwise the generic fallback applies.
     */
    default List<IncidentResolutionRule> incidentResolutionRules() {
        return List.of();
    }

    /**
     * Fast policy gate that can reject a mutation attempt before dispatch.
     */
    default boolean canAttemptResolution(IncidentResolutionContext context) {
        return buildResolutionDecision(context).allowed();
    }

    /**
     * Returns the strategy-owned incident-resolution policy decision.
     */
    default IncidentResolutionDecision buildResolutionDecision(IncidentResolutionContext context) {
        if (!supportsIncidentResolution()) {
            return IncidentResolutionDecision.blocked(
                    "Incident resolution is not supported for workflow " + getProcessDefinitionId() + ".",
                    buildResolutionGuidance(context));
        }

        if (context.activeIncidents() == null || context.activeIncidents().isEmpty()) {
            return IncidentResolutionDecision.noAction(
                    "No active incidents were supplied for workflow " + getProcessDefinitionId() + ".",
                    "No retry action was selected because Camunda evidence did not show an active incident for this workflow instance."
            );
        }

        IncidentResolutionDecision allowedDecision = null;
        for (Map<String, Object> incident : context.activeIncidents()) {
            for (IncidentResolutionRule rule : incidentResolutionRules()) {
                if (!rule.matches(context, incident)) {
                    continue;
                }

                IncidentResolutionDecision decision = rule.toDecision();
                if (!decision.allowed()) {
                    return decision;
                }
                if (allowedDecision == null) {
                    allowedDecision = decision;
                }
            }
        }

        if (allowedDecision != null) {
            return allowedDecision;
        }

        return IncidentResolutionDecision.allowed(
                preferredResolutionMode(),
                "Incident resolution is supported for workflow " + getProcessDefinitionId() + "."
        );
    }

    /**
     * Workflow-specific user-facing guidance to return when resolution is blocked or skipped.
     */
    default String buildResolutionGuidance(IncidentResolutionContext context) {
        return "Retry or resolution is allowed for workflow " + getProcessDefinitionId() + " unless a workflow-specific strategy blocks it.";
    }

    /**
     * NEW: Directives guiding how the agent structures its final Markdown analytical report
     * based on the custom variables and hierarchical sub-processes of this specific workflow.
     */
    String generateReportStructuringInstructions();
}
