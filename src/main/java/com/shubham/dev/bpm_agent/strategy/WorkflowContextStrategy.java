package com.shubham.dev.bpm_agent.strategy;

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
     * NEW: Directives guiding how the agent structures its final Markdown analytical report
     * based on the custom variables and hierarchical sub-processes of this specific workflow.
     */
    String generateReportStructuringInstructions();
}
