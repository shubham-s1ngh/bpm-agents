package com.shubham.dev.bpm_agent.chat.model.incident;

public enum IncidentResolutionMode {
    BY_PROCESS_INSTANCE,
    BY_INCIDENT_KEY,
    BLOCKED,
    NO_ACTION;

    public boolean allowsMutation() {
        return this == BY_PROCESS_INSTANCE || this == BY_INCIDENT_KEY;
    }
}
