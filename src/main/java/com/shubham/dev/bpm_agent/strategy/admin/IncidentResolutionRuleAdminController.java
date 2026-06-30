package com.shubham.dev.bpm_agent.strategy.admin;

import com.shubham.dev.bpm_agent.strategy.persistence.IncidentResolutionRuleManagementService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/incident-resolution-rules")
public class IncidentResolutionRuleAdminController {

    private final IncidentResolutionRuleManagementService managementService;
    private final BpmnIncidentRuleSuggestionService bpmnIncidentRuleSuggestionService;

    public IncidentResolutionRuleAdminController(IncidentResolutionRuleManagementService managementService,
                                                 BpmnIncidentRuleSuggestionService bpmnIncidentRuleSuggestionService) {
        this.managementService = managementService;
        this.bpmnIncidentRuleSuggestionService = bpmnIncidentRuleSuggestionService;
    }

    @GetMapping
    public List<IncidentResolutionRuleAdminRecord> listRules(
            @RequestParam(name = "workflowProcessDefinitionId", required = false) String workflowProcessDefinitionId) {
        return managementService.listRules(Optional.ofNullable(workflowProcessDefinitionId));
    }

    @GetMapping("/metadata")
    public IncidentResolutionRuleAdminMetadata metadata() {
        return managementService.fetchMetadata();
    }

    @PostMapping(path = "/suggestions", consumes = "multipart/form-data")
    public BpmnRuleSuggestionResponse suggestRulesFromBpmn(@RequestParam("files") List<MultipartFile> files,
                                                           @RequestParam(name = "consultantNotes", required = false) String consultantNotes) {
        return bpmnIncidentRuleSuggestionService.suggestRules(files, consultantNotes);
    }

    @PostMapping
    public ResponseEntity<IncidentResolutionRuleAdminRecord> createRule(@RequestBody IncidentResolutionRuleUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(managementService.createRule(request));
    }

    @PutMapping("/{ruleId}")
    public IncidentResolutionRuleAdminRecord updateRule(@PathVariable Long ruleId,
                                                        @RequestBody IncidentResolutionRuleUpsertRequest request) {
        return managementService.updateRule(ruleId, request);
    }

    @PatchMapping("/{ruleId}/enabled")
    public IncidentResolutionRuleAdminRecord setEnabled(@PathVariable Long ruleId,
                                                        @RequestBody EnabledToggleRequest request) {
        return managementService.setEnabled(ruleId, request != null && request.enabled());
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> deleteRule(@PathVariable Long ruleId) {
        managementService.deleteRule(ruleId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
    }

    public record EnabledToggleRequest(boolean enabled) {
    }
}
