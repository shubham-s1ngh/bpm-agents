package com.shubham.dev.bpm_agent.chat;

import com.shubham.dev.bpm_agent.chat.service.CamundaAgentChatService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP adapter for the local Camunda diagnostic agent.
 *
 * <p>This controller owns only request validation and HTTP response mapping.
 * All orchestration, tool execution, evidence shaping, and grounding logic is
 * delegated to {@link CamundaAgentChatService} to keep the web layer aligned
 * with single-responsibility boundaries.</p>
 */
@RestController
@RequestMapping("/api/llama")
public class LlamaToolsTestController {

    private final CamundaAgentChatService camundaAgentChatService;

    public LlamaToolsTestController(CamundaAgentChatService camundaAgentChatService) {
        this.camundaAgentChatService = camundaAgentChatService;
    }

    @PostMapping(path = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> chat(@RequestBody ToolChatRequest request) {
        if (request == null || !StringUtils.hasText(request.prompt())) {
            return ResponseEntity.badRequest().body("Prompt must not be empty.");
        }

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(camundaAgentChatService.handlePrompt(request.prompt()));
    }

    @PostMapping(path = "/resolve-incident", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> resolveIncidentByKey(@RequestBody ResolveIncidentRequest request) {
        if (request == null || !StringUtils.hasText(request.incidentKey())) {
            return ResponseEntity.badRequest().body("incidentKey must not be empty.");
        }

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(camundaAgentChatService.resolveIncidentByKey(request.incidentKey()));
    }

    @PostMapping(path = "/resolve-process-incidents", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> resolveIncidentsByProcessInstance(@RequestBody ResolveProcessIncidentsRequest request) {
        if (request == null || !StringUtils.hasText(request.processInstanceKey())) {
            return ResponseEntity.badRequest().body("processInstanceKey must not be empty.");
        }

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(camundaAgentChatService.resolveIncidentsByProcessInstance(request.processInstanceKey()));
    }

    public record ToolChatRequest(String prompt) {
    }

    public record ResolveIncidentRequest(String incidentKey) {
    }

    public record ResolveProcessIncidentsRequest(String processInstanceKey) {
    }
}
