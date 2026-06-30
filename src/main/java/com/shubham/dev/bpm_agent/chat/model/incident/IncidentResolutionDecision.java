package com.shubham.dev.bpm_agent.chat.model.incident;

public record IncidentResolutionDecision(IncidentResolutionMode mode,
                                         String reason,
                                         String userFacingGuidance) {

    public static IncidentResolutionDecision allowed(IncidentResolutionMode mode, String reason) {
        return new IncidentResolutionDecision(mode, reason, "");
    }

    public static IncidentResolutionDecision blocked(String reason, String guidance) {
        return new IncidentResolutionDecision(IncidentResolutionMode.BLOCKED, reason, guidance);
    }

    public static IncidentResolutionDecision noAction(String reason, String guidance) {
        return new IncidentResolutionDecision(IncidentResolutionMode.NO_ACTION, reason, guidance);
    }

    public boolean allowed() {
        return mode != null && mode.allowsMutation();
    }
}
