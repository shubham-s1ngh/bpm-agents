package com.shubham.dev.bpm_agent.chat.model.incident;

public record IncidentResolutionDecision(
        boolean allowed,
        IncidentResolutionMode mode,
        String reason,
        String userFacingGuidance
) {
    public static IncidentResolutionDecision allowed(IncidentResolutionMode mode, String reason) {
        return new IncidentResolutionDecision(true, mode, reason, "");
    }

    public static IncidentResolutionDecision blocked(String reason, String userFacingGuidance) {
        return new IncidentResolutionDecision(false, IncidentResolutionMode.BLOCKED, reason, userFacingGuidance);
    }

    public static IncidentResolutionDecision noAction(String reason, String userFacingGuidance) {
        return new IncidentResolutionDecision(false, IncidentResolutionMode.NO_ACTION, reason, userFacingGuidance);
    }
}
