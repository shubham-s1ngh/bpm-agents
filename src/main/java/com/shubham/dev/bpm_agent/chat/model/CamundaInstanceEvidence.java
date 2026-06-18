package com.shubham.dev.bpm_agent.chat.model;

/**
 * Canonical evidence for a single process instance discovered in a diagnostic tree.
 *
 * <p>This record keeps only the fields needed for semantic grounding checks:
 * instance key, process definition identifier, instance state, and incident count.</p>
 */
public record CamundaInstanceEvidence(String key, String processDefinitionId, String state, int incidentCount) {
}
