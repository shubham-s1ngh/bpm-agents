package com.shubham.dev.bpm_agent.chat.model;

import java.util.Map;
import java.util.Set;

/**
 * Immutable evidence bundle extracted from a Camunda diagnostic payload.
 *
 * <p>The snapshot is the normalized input for report generation and grounding
 * validation. It keeps the canonical digest shown to the model together with
 * token sets and per-instance metadata required for evidence checks.</p>
 */
public record CamundaEvidenceSnapshot(
        String digest,
        Set<String> allowedNumbers,
        Set<String> allowedProcessLikeIdentifiers,
        Map<String, CamundaInstanceEvidence> instancesByKey
) {
}
