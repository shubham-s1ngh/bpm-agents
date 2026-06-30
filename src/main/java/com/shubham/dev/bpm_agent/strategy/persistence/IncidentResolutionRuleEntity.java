package com.shubham.dev.bpm_agent.strategy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "incident_resolution_rule")
public class IncidentResolutionRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_process_definition_id", nullable = false)
    private String workflowProcessDefinitionId;

    @Column(nullable = false)
    private Integer priority;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 1000)
    private String instruction;

    @Column(name = "error_types", length = 500)
    private String errorTypes;

    @Column(name = "http_status_codes", length = 200)
    private String httpStatusCodes;

    @Column(name = "message_contains", length = 1000)
    private String messageContains;

    @Column(name = "resolution_mode", nullable = false, length = 50)
    private String resolutionMode;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "user_facing_guidance", length = 2000)
    private String userFacingGuidance;

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
}
