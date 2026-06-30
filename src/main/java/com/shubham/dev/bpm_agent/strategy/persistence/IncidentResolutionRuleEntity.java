package com.shubham.dev.bpm_agent.strategy.persistence;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "INCIDENT_RESOLUTION_RULE")
public class IncidentResolutionRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "WORKFLOW_PROCESS_DEFINITION_ID", nullable = false)
    private String workflowProcessDefinitionId;

    @Column(name = "PRIORITY", nullable = false)
    private Integer priority = 100;

    @Column(name = "ENABLED", nullable = false)
    private boolean enabled = true;

    @Column(name = "INSTRUCTION", nullable = false, length = 4000)
    private String instruction;

    @Column(name = "ERROR_TYPES", length = 1000)
    private String errorTypes;

    @Column(name = "HTTP_STATUS_CODES", length = 1000)
    private String httpStatusCodes;

    @Column(name = "MESSAGE_CONTAINS", length = 2000)
    private String messageContains;

    @Column(name = "RESOLUTION_MODE", nullable = false, length = 64)
    private String resolutionMode;

    @Column(name = "REASON", nullable = false, length = 4000)
    private String reason;

    @Column(name = "USER_FACING_GUIDANCE", length = 4000)
    private String userFacingGuidance;

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getWorkflowProcessDefinitionId() {
        return workflowProcessDefinitionId;
    }

    public void setWorkflowProcessDefinitionId(String workflowProcessDefinitionId) {
        this.workflowProcessDefinitionId = workflowProcessDefinitionId;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    public String getErrorTypes() {
        return errorTypes;
    }

    public void setErrorTypes(String errorTypes) {
        this.errorTypes = errorTypes;
    }

    public String getHttpStatusCodes() {
        return httpStatusCodes;
    }

    public void setHttpStatusCodes(String httpStatusCodes) {
        this.httpStatusCodes = httpStatusCodes;
    }

    public String getMessageContains() {
        return messageContains;
    }

    public void setMessageContains(String messageContains) {
        this.messageContains = messageContains;
    }

    public String getResolutionMode() {
        return resolutionMode;
    }

    public void setResolutionMode(String resolutionMode) {
        this.resolutionMode = resolutionMode;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getUserFacingGuidance() {
        return userFacingGuidance;
    }

    public void setUserFacingGuidance(String userFacingGuidance) {
        this.userFacingGuidance = userFacingGuidance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
